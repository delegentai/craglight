# Release signing

CragLight has an independently generated RSA-4096 signing identity. The key and its credentials are stored under the local project's `private/` directory, excluded from Git and restricted to the current Windows account. They are not copied from any other application and must never be published in a release archive.

Keep an encrypted backup of both `private/craglight-release.jks` and `private/signing.properties` in a secure location controlled by the publisher. Losing this key can prevent direct-APK upgrades; Play App Signing has its own separate upload-key recovery process. Do not regenerate or overwrite this identity for later releases.

`scripts/create-signing-key.ps1` refuses to overwrite existing signing material. `app/build.gradle.kts` reads the private signing properties only when they exist. Public CI builds do not receive them. Release checks must verify the certificate, package ID, APK signature, AAB signature and SHA-256 of both files.

Never put passwords in command-line arguments or logs. The key-generation script passes its generated password through a process environment variable and clears it after use.
