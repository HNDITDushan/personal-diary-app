# My Diary (Android)

A personal day-to-day diary and organiser. Kotlin, Jetpack Compose (Material 3), Room database. Android 8.0+.

## Features

| Tab | What it does |
|---|---|
| **Today** | Greeting, rate the day with a smiley (😊 Good / 😐 Fair / 😞 Bad), quick add buttons, tasks due, today's appointments, birthdays coming up in the next 14 days, today's diary entries |
| **Tasks** | To-do list with due date, priority, notes; filter To do / Done / All; overdue tasks shown in red |
| **Diary** | Daily **Activities**, **Memories** and **Good deeds** (who you helped), grouped by day |
| **Events** | **Appointments** (with reminder notification) and **Special days**: birthdays, anniversaries, other yearly days. **Sync from contacts** imports birthdays and anniversaries from your Google contacts |
| **Money** | **Dashboard** (balances, saved or overspent, 6-month chart, spending by category, card spending, payments, interest and fees), **Entries** (income, expenses, transfers, card payments), **Accounts** (cash, bank, credit cards), **Credit cards** (upload PDF statements), **Loans** (balance, installments, principal and interest on each payment, months and interest left, due-date reminders) |
| **More → Places I visited** | Save a place with your current GPS location (address filled in automatically); open it in Google Maps |
| **More → Monthly report** | Happiness score for the month, with a verdict (happy / mixed / unhappy), a mood calendar, the list of good deeds ("How I helped others"), and counts of tasks, activities, memories and places |
| **More → Google Sheet** | Two-way sync with a Google Sheet: everything in the app is copied to the sheet (one tab each for Tasks, Appointments, Activities, Memories, Good deeds, Special days, Places, Moods), and rows you add, change or delete in the sheet are applied in the app. Syncs about a minute after each change and every hour, or on demand |
| **More → Backup & restore** | Back up everything to one `.json` file, saved to the phone **or Google Drive** (pick "Drive" in the file picker). Restore from either location |

Daily notification at 8:00 for today's birthdays and special days, tasks due, and appointments. On the last day of each month a notification reminds you to check your monthly report.

The happiness score counts a Good day as 100, Fair as 50 and Bad as 0, then averages over the days you rated. 70 or more means happy, 45–69 mixed, below 45 unhappy.

## Build & install

Open this folder in **Android Studio** and press Run, or from a terminal:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone, or install it over USB with `adb install`.

## Credit card statements

Under **Money → Credit cards → Upload statement**, pick a statement PDF. If it is locked, the app asks for its password and can remember it per card, encrypted with an Android Keystore key. Saved passwords are left out of backups, so they must be typed again on a new phone.

The app reads lines that start with a date and end with an amount, then shows them for review before saving anything. It compares the lines with the statement's own balances and warns about a difference. Lines already imported are left unticked, and tapping a statement lets you remove it with its entries. PDFs are not copied into the app, long card numbers are masked, and scanned image PDFs can't be read.

Transfers and card payments only move money between your own accounts, so they don't count as income or spending. Only the interest part of a loan payment counts as a cost, and money received from a loan is not income. Money and loan data is included in backup files but is not synced to the Google Sheet.

## Google Sheet set-up

The app talks to a small Google Apps Script ([app/src/main/res/raw/sheet_script.js](app/src/main/res/raw/sheet_script.js)) attached to your sheet, so no Google Cloud project or API key is needed.

1. Create an empty Google Sheet (`sheets.new`).
2. **Extensions → Apps Script**, replace the code with the script. In the app, **More → Google Sheet → Copy script** (or **Send to computer**) gives you the script with your secret key already filled in.
3. **Deploy → New deployment → Web app**, *Execute as:* Me, *Who has access:* Anyone → **Deploy**, and authorise it.
4. Paste the **Web app URL** into the app and tap **Connect**. The first sync creates the tabs.

After every sync the script also rebuilds two read-only tabs at the front of the sheet:

- **Dashboard**: this month's happiness score and verdict, counts (days rated, good deeds, activities, places, tasks done), a colour-coded mood calendar, birthdays and special days in the next 30 days, upcoming appointments, this month's good deeds, and a 12-month trend table with charts (happiness, days by mood, good deeds).
- **Timeline**: everything recorded, newest first, with type, details and the day's mood; it has a filter on the header row.

The sheet's **My Diary → Refresh Dashboard & Timeline** menu rebuilds them after hand edits. If you set up the sheet with an older script, paste the new one and use **Deploy → Manage deployments → Edit → New version**; the web app URL stays the same.

When the same record is changed in both the app and the sheet between syncs, the app's version wins. The grey `uid` and `updatedAt` columns are bookkeeping; leave them empty for new rows.

## Notes

- **Google birthdays** are read from the phone's contacts. Android syncs your Google account's contacts to the phone, so no Google sign-in or API key is needed.
- **Google Drive backup** goes through Android's file picker, so the Google Drive app must be installed and signed in. Android's own automatic backup (Backup by Google One) also includes this app's data.
- All data stays on the device in a local database (`diary.db`).
