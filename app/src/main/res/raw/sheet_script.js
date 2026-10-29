/**
 * My Diary ⇄ Google Sheet sync.
 *
 * Paste this whole file into Extensions → Apps Script of a Google Sheet, then
 * Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone).
 * Copy the web app URL into My Diary → More → Google Sheet.
 *
 * The app creates the tabs (Tasks, Appointments, Activities, ...) on its first sync.
 * Add or edit rows in those tabs and they come into the app on the next sync.
 * Leave the grey "uid" and "updatedAt" columns alone; they are filled in for you.
 *
 * The Dashboard and Timeline tabs are rebuilt after every sync (or from the My Diary menu).
 * They are views only: edits there are overwritten.
 */

// Secret key shared with the app. My Diary fills this in when you use "Copy script".
const TOKEN = '__TOKEN__';

function doGet(e) {
  if (!e || !e.parameter || e.parameter.token !== TOKEN) return reply({ error: 'Wrong secret key' });
  const ss = SpreadsheetApp.getActive();
  const tabs = {};
  ss.getSheets().forEach(function (sheet) {
    if (VIEW_TABS.indexOf(sheet.getName()) >= 0) return;
    tabs[sheet.getName()] = readRows(sheet, ss.getSpreadsheetTimeZone());
  });
  return reply({ now: Date.now(), tabs: tabs, url: ss.getUrl(), name: ss.getName() });
}

function doPost(e) {
  const body = JSON.parse(e.postData.contents);
  if (body.token !== TOKEN) return reply({ error: 'Wrong secret key' });
  const lock = LockService.getDocumentLock();
  lock.waitLock(30000);
  try {
    const ss = SpreadsheetApp.getActive();
    const now = Date.now();
    Object.keys(body.tabs).forEach(function (name) {
      writeTab(ss, name, body.tabs[name], now);
    });
    SpreadsheetApp.flush();
    try {
      refreshViews();
    } catch (err) {
      console.error('Could not rebuild Dashboard/Timeline: ' + err);
    }
    return reply({ now: now });
  } finally {
    lock.releaseLock();
  }
}

/** Stamps updatedAt (and a uid for new rows) whenever you edit a row by hand. */
function onEdit(e) {
  const sheet = e.range.getSheet();
  const lastCol = sheet.getLastColumn();
  if (lastCol === 0) return;
  const header = sheet.getRange(1, 1, 1, lastCol).getValues()[0];
  const uidCol = header.indexOf('uid') + 1;
  const updatedCol = header.indexOf('updatedAt') + 1;
  if (updatedCol === 0) return;
  const firstRow = Math.max(e.range.getRow(), 2);
  const lastRow = e.range.getLastRow();
  for (let r = firstRow; r <= lastRow; r++) {
    const values = sheet.getRange(r, 1, 1, lastCol).getValues()[0];
    const hasData = values.some(function (v, i) {
      return i + 1 !== uidCol && i + 1 !== updatedCol && v !== '';
    });
    if (!hasData) continue;
    if (uidCol > 0 && values[uidCol - 1] === '') sheet.getRange(r, uidCol).setValue(Utilities.getUuid());
    sheet.getRange(r, updatedCol).setValue(Date.now());
  }
}

function readRows(sheet, tz) {
  const lastRow = sheet.getLastRow();
  const lastCol = sheet.getLastColumn();
  if (lastRow < 2 || lastCol === 0) return [];
  const data = sheet.getRange(1, 1, lastRow, lastCol).getValues();
  const header = data[0].map(String);
  const rows = [];
  for (let r = 1; r < data.length; r++) {
    const row = {};
    let empty = true;
    header.forEach(function (name, c) {
      if (!name) return;
      const text = cellText(data[r][c], tz);
      if (text !== '' && name !== 'uid' && name !== 'updatedAt') empty = false;
      row[name] = text;
    });
    if (!empty) rows.push(row);
  }
  return rows;
}

/** Dates become yyyy-MM-dd, times HH:mm, date-times yyyy-MM-dd HH:mm. */
function cellText(v, tz) {
  if (v === '' || v === null) return '';
  if (v instanceof Date) {
    if (v.getFullYear() < 1900) return Utilities.formatDate(v, tz, 'HH:mm');
    const hm = Utilities.formatDate(v, tz, 'HH:mm');
    return Utilities.formatDate(v, tz, hm === '00:00' ? 'yyyy-MM-dd' : 'yyyy-MM-dd HH:mm');
  }
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  return String(v);
}

/** Replaces a tab's rows with the merged rows sent by the app. */
function writeTab(ss, name, tab, now) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) sheet = ss.insertSheet(name);
  const columns = tab.columns;
  const names = columns.map(function (c) { return c.name; });
  const width = names.length;

  sheet.clearContents();
  sheet.getRange(1, 1, 1, width).setValues([names]).setFontWeight('bold');
  sheet.setFrozenRows(1);

  const rows = tab.rows.map(function (row) {
    return names.map(function (n) {
      if (n === 'updatedAt') return now;
      const v = row[n];
      return v === undefined || v === null ? '' : v;
    });
  });
  if (rows.length > 0) sheet.getRange(2, 1, rows.length, width).setValues(rows);

  // Dropdowns for fixed choices, and greyed-out bookkeeping columns.
  const maxRows = Math.max(sheet.getMaxRows() - 1, 1);
  columns.forEach(function (c, i) {
    const range = sheet.getRange(2, i + 1, maxRows, 1);
    if (c.choices) {
      range.setDataValidation(SpreadsheetApp.newDataValidation()
        .requireValueInList(c.choices, true).setAllowInvalid(true).build());
    }
    if (c.name === 'uid' || c.name === 'updatedAt') {
      sheet.getRange(1, i + 1, maxRows + 1, 1).setFontColor('#9e9e9e');
    }
  });
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

// ---------------------------------------------------------------------------
// Dashboard and Timeline views
// ---------------------------------------------------------------------------

const VIEW_TABS = ['Dashboard', 'Timeline'];

const MOOD_STYLE = {
  Good: { emoji: '😊', color: '#c8e6c9' },
  Fair: { emoji: '😐', color: '#fff3c4' },
  Bad: { emoji: '😞', color: '#ffcdd2' },
};

const KINDS = {
  activity: { label: '📝 Activity', color: '#e8eaf6' },
  memory: { label: '✨ Memory', color: '#f3e5f5' },
  deed: { label: '💛 Good deed', color: '#fce4ec' },
  place: { label: '📍 Place', color: '#e0f2f1' },
  task: { label: '✅ Task done', color: '#e8f5e9' },
  appointment: { label: '📅 Appointment', color: '#fff3e0' },
  mood: { label: '🙂 Mood', color: '#f5f5f5' },
};

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('My Diary')
    .addItem('Refresh Dashboard & Timeline', 'refreshViews')
    .addToUi();
}

function refreshViews() {
  const ss = SpreadsheetApp.getActive();
  const tz = ss.getSpreadsheetTimeZone();
  const data = loadData(ss, tz);
  buildTimeline(ss, data);
  buildDashboard(ss, data, tz);
  placeFirst(ss, 'Timeline');
  placeFirst(ss, 'Dashboard');
}

/** Reads every data tab into plain objects keyed by column name. */
function loadData(ss, tz) {
  const read = function (name) {
    const sheet = ss.getSheetByName(name);
    if (!sheet || sheet.getLastRow() < 2) return [];
    const values = sheet.getRange(1, 1, sheet.getLastRow(), sheet.getLastColumn()).getValues();
    const header = values[0].map(String);
    return values.slice(1).map(function (r) {
      const o = {};
      header.forEach(function (h, i) { o[h] = r[i]; });
      return o;
    });
  };
  const moods = {};
  read('Moods').forEach(function (r) {
    const day = dayKey(r['Date'], tz);
    const mood = moodName(r['Mood']);
    if (day && mood) moods[day] = { mood: mood, note: String(r['Note'] || '') };
  });
  return {
    activities: read('Activities'),
    memories: read('Memories'),
    deeds: read('Good deeds'),
    places: read('Places'),
    tasks: read('Tasks'),
    appointments: read('Appointments'),
    special: read('Special days'),
    moods: moods,
  };
}

/** "yyyy-MM-dd" for a sheet date cell or typed date text; '' when it isn't a date. */
function dayKey(v, tz) {
  if (v instanceof Date) return v.getFullYear() < 1900 ? '' : Utilities.formatDate(v, tz, 'yyyy-MM-dd');
  const s = String(v || '').trim();
  let m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (m) return m[1] + '-' + pad(m[2]) + '-' + pad(m[3]);
  m = s.match(/^(\d{1,2})[\/.-](\d{1,2})[\/.-](\d{4})/);
  if (m) return m[3] + '-' + pad(m[2]) + '-' + pad(m[1]);
  return '';
}

function timeKey(v, tz) {
  if (v instanceof Date) return Utilities.formatDate(v, tz, 'HH:mm');
  const m = String(v || '').match(/(\d{1,2}):(\d{2})/);
  return m ? pad(m[1]) + ':' + m[2] : '';
}

function pad(n) { return ('0' + n).slice(-2); }

function moodName(v) {
  const s = String(v || '').trim().toLowerCase();
  if (s.indexOf('good') === 0 || s === '😊' || s === 'happy') return 'Good';
  if (s.indexOf('fair') === 0 || s === '😐' || s === 'ok' || s === 'okay') return 'Fair';
  if (s.indexOf('bad') === 0 || s === '😞' || s === 'sad') return 'Bad';
  return '';
}

function isTrue(v) { return v === true || String(v).toLowerCase() === 'true'; }

function joinParts(parts) {
  return parts.filter(function (p) { return p !== '' && p !== null && p !== undefined; }).join(' · ');
}

/** Every recorded item as {day, time, kind, title, details}. */
function timelineItems(data, tz) {
  const items = [];
  const add = function (kind, day, time, title, details) {
    if (day && String(title).trim() !== '') items.push({ day: day, time: time, kind: kind, title: String(title), details: details });
  };
  data.activities.forEach(function (r) {
    add('activity', dayKey(r['Date'], tz), timeKey(r['Time'], tz), r['Title'],
      joinParts([r['Details'], r['With'] ? 'With ' + r['With'] : '']));
  });
  data.memories.forEach(function (r) {
    add('memory', dayKey(r['Date'], tz), timeKey(r['Time'], tz), r['Title'],
      joinParts([r['Details'], r['People'] ? 'With ' + r['People'] : '']));
  });
  data.deeds.forEach(function (r) {
    add('deed', dayKey(r['Date'], tz), timeKey(r['Time'], tz), r['Title'],
      joinParts([r['Who I helped'] ? 'Helped ' + r['Who I helped'] : '', r['Details']]));
  });
  data.places.forEach(function (r) {
    add('place', dayKey(r['Date'], tz), '', r['Name'], joinParts([r['Address'], r['Notes']]));
  });
  data.tasks.forEach(function (r) {
    if (isTrue(r['Done'])) add('task', dayKey(r['Completed'], tz), timeKey(r['Completed'], tz), r['Title'], String(r['Notes'] || ''));
  });
  data.appointments.forEach(function (r) {
    add('appointment', dayKey(r['Date'], tz), timeKey(r['Time'], tz), r['Title'], joinParts([r['Location'], r['Notes']]));
  });
  return items;
}

function freshSheet(ss, name) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) sheet = ss.insertSheet(name);
  if (sheet.getFilter()) sheet.getFilter().remove();
  sheet.getCharts().forEach(function (c) { sheet.removeChart(c); });
  sheet.getRange(1, 1, sheet.getMaxRows(), sheet.getMaxColumns()).breakApart();
  sheet.clear();
  sheet.clearConditionalFormatRules();
  sheet.getDataRange().clearDataValidations();
  sheet.setFrozenRows(0);
  return sheet;
}

function placeFirst(ss, name) {
  const sheet = ss.getSheetByName(name);
  if (!sheet) return;
  const active = ss.getActiveSheet();
  ss.setActiveSheet(sheet);
  ss.moveActiveSheet(1);
  if (active && active.getName() !== name) ss.setActiveSheet(active);
}

function buildTimeline(ss, data) {
  const tz = ss.getSpreadsheetTimeZone();
  const sheet = freshSheet(ss, 'Timeline');
  const items = timelineItems(data, tz);
  const days = {};
  items.forEach(function (it) { days[it.day] = true; });
  // A rated day with nothing else recorded still shows up.
  Object.keys(data.moods).forEach(function (day) {
    if (!days[day]) items.push({ day: day, time: '', kind: 'mood', title: data.moods[day].mood + ' day', details: data.moods[day].note });
  });
  items.sort(function (a, b) {
    if (a.day !== b.day) return a.day < b.day ? 1 : -1;
    return (a.time || '') < (b.time || '') ? 1 : (a.time || '') > (b.time || '') ? -1 : 0;
  });

  const header = ['Date', 'Time', 'Type', 'What', 'Details', 'Mood of the day'];
  sheet.getRange(1, 1, 1, header.length).setValues([header])
    .setFontWeight('bold').setBackground('#7b4fa0').setFontColor('#ffffff');
  sheet.setFrozenRows(1);
  [110, 70, 130, 260, 380, 120].forEach(function (w, i) { sheet.setColumnWidth(i + 1, w); });

  if (items.length === 0) {
    sheet.getRange(2, 1).setValue('Nothing recorded yet. Add entries in the app or in the data tabs.');
    return;
  }
  const rows = [];
  const backgrounds = [];
  let band = false;
  let lastDay = null;
  items.forEach(function (it) {
    if (it.day !== lastDay) { band = !band; lastDay = it.day; }
    const mood = data.moods[it.day];
    const base = band ? '#ffffff' : '#faf7fc';
    rows.push([it.day, it.time, KINDS[it.kind].label, it.title, it.details, mood ? MOOD_STYLE[mood.mood].emoji + ' ' + mood.mood : '']);
    backgrounds.push([base, base, KINDS[it.kind].color, base, base, mood ? MOOD_STYLE[mood.mood].color : base]);
  });
  const range = sheet.getRange(2, 1, rows.length, header.length);
  sheet.getRange(2, 1, rows.length, 2).setNumberFormat('@'); // keep dates and times exactly as written
  range.setValues(rows).setBackgrounds(backgrounds).setVerticalAlignment('top');
  sheet.getRange(2, 4, rows.length, 2).setWrap(true);
  sheet.getRange(1, 1, rows.length + 1, header.length).createFilter();
}

function monthLabel(ym) {
  const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return names[Number(ym.slice(5, 7)) - 1] + ' ' + ym.slice(0, 4);
}

function happiness(good, fair, rated) {
  return rated === 0 ? '' : Math.round((good * 100 + fair * 50) / rated);
}

function verdict(score) {
  if (score === '') return 'Rate your days in the app to see your score';
  if (score >= 70) return '😊 Happy month';
  if (score >= 45) return '😐 Mixed month';
  return '😞 Unhappy month';
}

/** Writes a titled table and returns the next free row. */
function writeTable(sheet, row, col, title, header, rows, emptyText) {
  sheet.getRange(row, col).setValue(title).setFontWeight('bold').setFontSize(13).setFontColor('#7b4fa0');
  sheet.getRange(row + 1, col, 1, header.length).setValues([header])
    .setFontWeight('bold').setBackground('#ede7f6');
  if (rows.length === 0) {
    sheet.getRange(row + 2, col).setValue(emptyText).setFontColor('#757575');
    return row + 4;
  }
  sheet.getRange(row + 2, col, rows.length, header.length).setNumberFormat('@').setValues(rows).setWrap(true).setVerticalAlignment('top');
  return row + rows.length + 3;
}

function buildDashboard(ss, data, tz) {
  const sheet = freshSheet(ss, 'Dashboard');
  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const thisMonth = today.slice(0, 7);
  const items = timelineItems(data, tz);
  for (let c = 1; c <= 15; c++) sheet.setColumnWidth(c, c === 8 ? 24 : 96);

  // Per-month statistics for the last 12 months.
  const months = [];
  const y0 = Number(thisMonth.slice(0, 4));
  const m0 = Number(thisMonth.slice(5, 7));
  for (let i = 11; i >= 0; i--) {
    const t = y0 * 12 + (m0 - 1) - i;
    months.push(Math.floor(t / 12) + '-' + pad((t % 12) + 1));
  }
  const stats = {};
  months.forEach(function (ym) {
    stats[ym] = { rated: 0, Good: 0, Fair: 0, Bad: 0, deed: 0, activity: 0, memory: 0, place: 0, task: 0 };
  });
  Object.keys(data.moods).forEach(function (day) {
    const s = stats[day.slice(0, 7)];
    if (s) { s.rated++; s[data.moods[day].mood]++; }
  });
  items.forEach(function (it) {
    const s = stats[it.day.slice(0, 7)];
    if (s && s[it.kind] !== undefined) s[it.kind]++;
  });
  const cur = stats[thisMonth];
  const score = happiness(cur.Good, cur.Fair, cur.rated);

  // Title and headline numbers.
  sheet.getRange(1, 1).setValue('📔 My Diary — Dashboard').setFontSize(20).setFontWeight('bold').setFontColor('#7b4fa0');
  sheet.getRange(2, 1).setValue('Updated ' + Utilities.formatDate(new Date(), tz, 'EEE d MMM yyyy, h:mm a') +
    ' · rebuilt on every sync, or use the My Diary menu → Refresh').setFontColor('#757575');
  sheet.getRange(4, 1).setValue(monthLabel(thisMonth) + ' — ' + verdict(score)).setFontSize(15).setFontWeight('bold');

  const cards = [
    ['Happiness', score === '' ? '–' : score + ' / 100'],
    ['Days rated', cur.rated + ' / ' + new Date(Date.UTC(y0, m0, 0)).getUTCDate()],
    ['😊 Good', cur.Good], ['😐 Fair', cur.Fair], ['😞 Bad', cur.Bad],
    ['💛 Good deeds', cur.deed], ['📝 Activities', cur.activity], ['📍 Places', cur.place], ['✅ Tasks done', cur.task],
  ];
  cards.forEach(function (card, i) {
    const col = i < 7 ? i + 1 : i + 2; // skip the narrow spacer column H
    sheet.getRange(6, col).setValue(card[0]).setFontColor('#616161').setHorizontalAlignment('center').setBackground('#f3eef8');
    sheet.getRange(7, col).setValue(String(card[1])).setNumberFormat('@').setFontSize(16).setFontWeight('bold')
      .setHorizontalAlignment('center').setBackground('#f3eef8');
  });
  sheet.setRowHeight(7, 36);

  // Mood calendar for this month (columns A–G).
  let left = 9;
  sheet.getRange(left, 1).setValue('Mood calendar — ' + monthLabel(thisMonth)).setFontWeight('bold').setFontSize(13).setFontColor('#7b4fa0');
  sheet.getRange(left + 1, 1, 1, 7).setValues([['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']])
    .setFontWeight('bold').setHorizontalAlignment('center').setBackground('#ede7f6');
  const firstDow = (new Date(Date.UTC(y0, m0 - 1, 1)).getUTCDay() + 6) % 7;
  const daysInMonth = new Date(Date.UTC(y0, m0, 0)).getUTCDate();
  const weeks = Math.ceil((firstDow + daysInMonth) / 7);
  const cal = [];
  const calBg = [];
  for (let w = 0; w < weeks; w++) {
    const row = [];
    const bg = [];
    for (let d = 0; d < 7; d++) {
      const n = w * 7 + d - firstDow + 1;
      if (n < 1 || n > daysInMonth) { row.push(''); bg.push('#ffffff'); continue; }
      const mood = data.moods[thisMonth + '-' + pad(n)];
      row.push(n + (mood ? '  ' + MOOD_STYLE[mood.mood].emoji : ''));
      bg.push(mood ? MOOD_STYLE[mood.mood].color : '#f5f5f5');
    }
    cal.push(row);
    calBg.push(bg);
  }
  sheet.getRange(left + 2, 1, weeks, 7).setNumberFormat('@').setValues(cal).setBackgrounds(calBg)
    .setHorizontalAlignment('center').setVerticalAlignment('middle');
  for (let w = 0; w < weeks; w++) sheet.setRowHeight(left + 2 + w, 32);
  left += weeks + 3;

  const deeds = items.filter(function (it) { return it.kind === 'deed' && it.day.slice(0, 7) === thisMonth; })
    .sort(function (a, b) { return a.day < b.day ? 1 : -1; })
    .map(function (it) { return [it.day.slice(8) + ' ' + monthLabel(thisMonth).slice(0, 3), it.title, it.details]; });
  const deedsTop = left;
  left = writeTable(sheet, left, 1, '💛 How I helped others this month', ['Date', 'Good deed', 'Details'], deeds,
    'No good deeds recorded this month yet.');
  // Let the details column run across C–G so long notes stay readable.
  for (let r = 0; r <= deeds.length; r++) sheet.getRange(deedsTop + 1 + r, 3, 1, 5).merge();

  // Upcoming special days and appointments (columns I–O).
  const ty = Number(today.slice(0, 4));
  const todayUtc = Date.UTC(ty, Number(today.slice(5, 7)) - 1, Number(today.slice(8, 10)));
  const upcoming = [];
  data.special.forEach(function (r) {
    const month = Number(r['Month']);
    const day = Number(r['Day']);
    if (!r['Name'] || !(month >= 1 && month <= 12) || !(day >= 1 && day <= 31)) return;
    let year = ty;
    let next = Date.UTC(year, month - 1, day);
    if (next < todayUtc) { year++; next = Date.UTC(year, month - 1, day); }
    const inDays = Math.round((next - todayUtc) / 86400000);
    if (inDays > 30) return;
    const born = Number(r['Year']);
    const type = String(r['Type'] || 'Birthday');
    const years = born > 1800 ? year - born : '';
    const emoji = type.indexOf('Anniv') === 0 ? '💞' : type.indexOf('Birth') === 0 ? '🎂' : '⭐';
    upcoming.push([inDays, [
      inDays === 0 ? 'Today 🎉' : inDays === 1 ? 'Tomorrow' : 'In ' + inDays + ' days',
      pad(day) + '/' + pad(month), emoji + ' ' + r['Name'], type,
      years === '' ? '' : (type.indexOf('Birth') === 0 ? 'Turns ' + years : years + ' years'),
    ]]);
  });
  upcoming.sort(function (a, b) { return a[0] - b[0]; });
  let right = writeTable(sheet, 9, 9, '🎂 Coming up in the next 30 days', ['When', 'Date', 'Name', 'Type', 'Age / years'],
    upcoming.map(function (u) { return u[1]; }), 'No birthdays or special days in the next 30 days.');

  const appts = data.appointments
    .map(function (r) { return { day: dayKey(r['Date'], tz), time: timeKey(r['Time'], tz), r: r }; })
    .filter(function (a) { return a.day && a.day >= today && a.r['Title']; })
    .sort(function (a, b) { return (a.day + a.time) < (b.day + b.time) ? -1 : 1; })
    .slice(0, 10)
    .map(function (a) { return [a.day, a.time, String(a.r['Title']), String(a.r['Location'] || '')]; });
  right = writeTable(sheet, right, 9, '📅 Upcoming appointments', ['Date', 'Time', 'Appointment', 'Where'], appts,
    'No upcoming appointments.');

  // Twelve-month trend table with charts below it.
  const top = Math.max(left, right) + 1;
  const trendHeader = ['Month', 'Days rated', 'Good', 'Fair', 'Bad', 'Happiness', 'Good deeds', '', 'Activities', 'Memories', 'Places', 'Tasks done'];
  const trend = months.map(function (ym) {
    const s = stats[ym];
    return [monthLabel(ym), s.rated, s.Good, s.Fair, s.Bad, happiness(s.Good, s.Fair, s.rated), s.deed, '', s.activity, s.memory, s.place, s.task];
  });
  sheet.getRange(top, 1).setValue('📈 Last 12 months').setFontWeight('bold').setFontSize(13).setFontColor('#7b4fa0');
  sheet.getRange(top + 1, 1, 1, trendHeader.length).setValues([trendHeader]).setFontWeight('bold').setBackground('#ede7f6');
  sheet.getRange(top + 2, 1, trend.length, 1).setNumberFormat('@');
  sheet.getRange(top + 2, 1, trend.length, trendHeader.length).setValues(trend).setHorizontalAlignment('center');
  const domain = sheet.getRange(top + 1, 1, trend.length + 1, 1);
  const chartsRow = top + trend.length + 3;

  sheet.insertChart(sheet.newChart().setChartType(Charts.ChartType.LINE)
    .addRange(domain).addRange(sheet.getRange(top + 1, 6, trend.length + 1, 1))
    .setOption('title', 'Happiness score (0–100)').setOption('legend', { position: 'none' })
    .setOption('vAxis', { viewWindow: { min: 0, max: 100 } }).setOption('colors', ['#7b4fa0'])
    .setOption('pointSize', 6).setOption('width', 460).setOption('height', 280)
    .setPosition(chartsRow, 1, 0, 0).build());

  sheet.insertChart(sheet.newChart().setChartType(Charts.ChartType.COLUMN)
    .addRange(domain).addRange(sheet.getRange(top + 1, 3, trend.length + 1, 3))
    .setStacked().setOption('title', 'Days by mood').setOption('colors', ['#43a047', '#f9a825', '#e53935'])
    .setOption('width', 460).setOption('height', 280)
    .setPosition(chartsRow, 6, 0, 0).build());

  sheet.insertChart(sheet.newChart().setChartType(Charts.ChartType.COLUMN)
    .addRange(domain).addRange(sheet.getRange(top + 1, 7, trend.length + 1, 1))
    .setOption('title', 'Good deeds per month').setOption('legend', { position: 'none' }).setOption('colors', ['#e0457b'])
    .setOption('width', 460).setOption('height', 280)
    .setPosition(chartsRow, 11, 0, 0).build());

  sheet.setHiddenGridlines(true);
}
