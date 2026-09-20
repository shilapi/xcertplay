# xcertplay

English | [中文](README.zh-CN.md)

`xcertplay` is an Android head-unit CarPlay receiver. It supports connecting to
the MFI chip through a CH341 I2C bridge or directly through the board's I2C
controller, and supports both wired and wireless CarPlay connections.

> Still under active development.

## Features

- CarPlay host applications for Android and Android Automotive OS.
- Support for MFI chips connected through a CH341 bridge or native
  `/dev/i2c-N` devices, and Remote MFI authentication (see the API below).
- Wired and wireless CarPlay connections.
- CarPlay Ultra triggering (the protocol stack is untested/incomplete, but it
  can trigger the CarPlay Ultra prompt on an iPhone).
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new
  resolution.
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Launch the app and tap `设置` in the top-left corner to open the Settings page. The button
   hides automatically once CarPlay is streaming, so configure everything before you connect.
3. Make sure all the settings are configured as desired.
4. Scroll to the bottom and select `Save & Reconnect`.
5. Connect your MFi chip using the method you selected.
6. Wait for the connection to complete, then enjoy.

## Current progress

It works 👍. It has been tested on car head units and phones. If you encounter
an incompatible car head unit, please open an issue and attach your log from
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`.

Adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

Waiting for the MFI chip adapter board to arrive...

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementations. |

## Remote MFI

The Remote MFi client treats a remote service as an MFi chip for remote calls,
or uses BAA authentication. Remote authentication avoids the process of
connecting to a local MFi chip for authentication.

### Endpoints

| Method | Path | Purpose | Request body | Success response | Failure response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and certificate contents; cached by the client after the first call | None | Certificate JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` | `{"detail":"..."}` |

(Optional) Standard Bearer Authentication can be used for verification.

**Currently, only BAA Authentication has been tested.**

## Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the
  Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- A physical USB Host/OTG Android device and MFi hardware are required for
  hardware validation.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI) for providing important
reference for this project.
Thanks to the [showcase](https://github.com/amineross/showcase) project for
providing important reference for the BAA authentication in this project.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
