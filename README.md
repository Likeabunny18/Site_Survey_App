# Site Survey

Android app for AV site surveys on a Pixel: capture a room in AR, place true-scale gear, export a dimensioned plan and equipment list.

## Install on your phone

Every push to `main` builds an APK and attaches it to the [latest release](../../releases/latest).
On the phone, signed in to GitHub, open that release, tap `site-survey.apk`, then **Install**.
Updates install over the old version and keep your surveys.

## How a survey goes

1. **Floor**: aim the crosshair at each floor corner and tap +. Aim back at the first corner (or tap ✓) to close. Four-corner rooms are squared up automatically.
2. **Ceiling**: aim where any corner meets the ceiling and tap +.
3. **Openings**: pick Door, Window or Opening, tap two opposite corners on the wall.
4. **Gear**: Display (on a wall, pick a size), Speaker or Ceiling mic (aim at the ceiling), Table mic (aim at the table top).
5. **View**: the client walkthrough.

Laser readings: tap the reading chip in AR, or the pencil on the survey screen and then any dimension. Type `12' 6"`, `12 6`, `150 in` or `3.81 m`, or tap the mic and say it. Enter saves.

Opening a saved survey in AR asks you to tap corner A then corner B to line the room back up.

## Build locally (optional)

Android Studio, open the folder, Run. Or `gradlew assembleDebug`.
