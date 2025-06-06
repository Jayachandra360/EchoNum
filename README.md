# EchoNum
This Android application empowers users to restrict internet access for selected apps exclusively to their foreground usage. When a controlled app is active on the screen, it has internet access; once it moves to the background, its internet connectivity is automatically disabled. 

Key Feature Logic with Real-Time Examples

1. *Foreground Access Only*

*Logic:* Internet access is allowed *only* for selected apps *while they are in the foreground* (actively in use). As soon as a selected app is minimized or sent to the background, its internet access is automatically blocked.
*Example:*

* You select YouTube and Instagram for internet control.
* While watching a video on YouTube, internet access remains active.
* When you switch to Gmail (not selected), YouTube immediately loses internet access.
* Gmail continues to function normally since it is not under control.

2. *Automatic Background Blocking*

*Logic:* The app continuously monitors the usage of selected apps. When any of these controlled apps move to the background, their internet access is automatically disabled. Unselected apps remain unaffected.
*Example:*

* You select Facebook for control.
* You scroll through Facebook, then switch to Google Maps (not selected).
* Facebook is automatically blocked in the background, while Google Maps continues working normally.

3. *Instant "Stop All" Functionality*

*Logic:* Toggling off the main control switch disables all monitoring and background services, including any VPN or accessibility-based restrictions.
*Example:*

* Several apps are selected for control.
* You turn off the master switch in the app.
* All restrictions are immediately lifted, and all apps regain unrestricted internet access.

4. *Uninterrupted Call & SMS Support (VPN Bypass)*

*Logic:* Even when VPN-based control is active, the system intelligently *bypasses VPN routing for phone and VoIP calls and SMS*, ensuring calls and messages are never blocked or delayed.
*Example:*

* VPN-based app control is enabled.
* You receive a WhatsApp or regular voice call, or an SMS.
* The call/SMS connects and functions seamlessly due to smart VPN exclusions.

Summary:
This app is ideal for users who want to:

* Save data by preventing selected apps from using the internet in the background
* Increase battery life by stopping hidden app activity
* Improve privacy by blocking silent background data usage
* Maintain uninterrupted phone calls and SMS*, even when VPN-based control is active
