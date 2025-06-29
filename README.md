# EchoNum
This Android application empowers users to restrict internet access for selected apps exclusively to their foreground usage. When a controlled app is active on the screen, it has internet access; once it moves to the background, its internet connectivity is automatically disabled. 

Andriod App Name Is(EchoNum):
📄 Technical Report: Foreground-Only Internet Access Control App

**Prepared for:** Jayachandra
**Email:** jayachandrareddy9676@gmail.com
**Date:** June 6, 2025

🔧 Project Overview

This Android application empowers users to restrict internet access for selected apps exclusively to their foreground usage. When a controlled app is active on the screen, it has internet access; once it moves to the background, its internet connectivity is automatically disabled. This functionality is achieved without requiring root access, utilizing Android's VPN and accessibility services.

🛠️ Key Features & Logic
1. **Foreground Access Only**

* **Logic:** Internet access is granted to selected apps solely during their active foreground usage. Once the app is minimized or sent to the background, its internet access is revoked.

* **Example:**

  * User selects YouTube and Instagram for control.
  * While watching a video on YouTube, internet access remains active.
  * Switching to Gmail (not selected) causes YouTube to lose internet access immediately.
  * Gmail continues to function normally as it's not under control.

2. **Automatic Background Blocking**

* **Logic:** The app monitors the usage of selected apps. When any controlled app transitions to the background, its internet access is automatically disabled. Unselected apps remain unaffected.([github.com][1])

* **Example:**

  * User selects Facebook for control.
  * Scrolling through Facebook, then switching to Google Maps (not selected), results in Facebook being blocked in the background, while Google Maps continues working normally.
3. **Instant "Stop All" Functionality**

* **Logic:** A master switch disables all monitoring and background services, including any VPN or accessibility-based restrictions.

* **Example:**

  * Multiple apps are selected for control.
  * Turning off the master switch lifts all restrictions, and all apps regain unrestricted internet access.

4. **Uninterrupted Call & SMS Support (VPN Bypass)**

* **Logic:** Even with VPN-based control active, the system intelligently bypasses VPN routing for phone and VoIP calls and SMS, ensuring calls and messages are never blocked or delayed.

* **Example:**

  * VPN-based app control is enabled.
  * Receiving a WhatsApp or regular voice call, or an SMS, functions seamlessly due to smart VPN exclusions.


🧠 Technical Implementation

* **VPNService API:** Utilizes Android's VPNService to create a local VPN interface, allowing control over network traffic without root access. ([medium.com][2])

* **AccessibilityService:** Monitors app transitions between foreground and background states to trigger internet access changes.

* **Per-App Network Control:** Implements per-app network access rules, enabling or disabling internet connectivity based on app state.

* **System App Exclusions:** Ensures essential system services like com.android.phone and com.android.messaging are excluded from VPN control to prevent disruption of core functionalities.

📈 Benefits

* **Data Savings:** Prevents selected apps from consuming data in the background.

* **Battery Conservation:** Reduces battery drain by limiting background network activity.

* **Enhanced Privacy:** Blocks silent background data usage, protecting user privacy.

* **Uninterrupted Communication:** Maintains seamless phone calls and SMS, even with VPN-based control active.

📄 Funding Request

* **Requested Amount:** $10,000

* **Purpose:**

  * Development and testing of the application.
  * User interface and experience enhancements.
  * Security audits and compliance checks.
  * Marketing and user acquisition efforts.

For further information or inquiries, please contact Jayachandra at [jayachandrareddy9676@gmail.com]
[1]: https://github.com/M66B/NetGuard?utm_source "M66B/NetGuard: A simple way to block access to the internet per app"
[2]: https://medium.com/%40satish.nada98/complete-guide-to-implementing-a-vpn-service-in-android-exploring-development-details-with-code-96683c834d8d?utm_source "Complete Guide to Implementing a VPN Service in Android - Medium"
[3]: https://en.wikipedia.org/wiki/Orbot?utm_source "Orbot"
[4]: https://en.wikipedia.org/wiki/Mobicip?utm_source "Mobicip"

Why this app useful:

- This app is great because it only lets the internet run when you're actively using the app.

1. More Privacy: No internet in the background means the app can't secretly send your data.
2. Better Security: It's harder for bad stuff to happen online when the app isn't connected in the background.
3. Saves Data: The app won't use your mobile data when you're not looking, saving you money.
Basically, it gives you more control and peace of mind!. can covert into json format
