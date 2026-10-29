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
 */

// Secret key shared with the app. My Diary fills this in when you use "Copy script".
const TOKEN = '__TOKEN__';

function doGet(e) {
  if (!e || !e.parameter || e.parameter.token !== TOKEN) return reply({ error: 'Wrong secret key' });
  const ss = SpreadsheetApp.getActive();
  const tabs = {};
  ss.getSheets().forEach(function (sheet) {
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
