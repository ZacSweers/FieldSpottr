# Contributing

## Maps Configuration

### Google Maps API Key (Android)

To enable native Google Maps on Android, you need to configure a Google Maps API key.

#### Setup:

1. **Get a Google Maps API Key:**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Enable the "Maps SDK for Android" API
   - Create an API key

2. **Configure the key:**
   - Add to `gradle.properties` (or `~/.gradle/gradle.properties`):
     ```properties
     fs_maps_api_key=YOUR_API_KEY_HERE
     ```

3. **Restrict the API key (recommended):**
   - In Google Cloud Console, restrict to Android apps
   - Add package name: `dev.zacsweers.fieldspottr` (or `dev.zacsweers.fieldspottr.debug` for debug builds)
   - Add SHA-1 certificate fingerprint

#### Fallback Behavior:

If no API key is configured, the Android app will:
- Show location coordinates and title
- Display a "Google Maps API key not configured" message  
- Provide a button to open the location in the browser/default maps app

This ensures the app works for contributors who don't have an API key set up.