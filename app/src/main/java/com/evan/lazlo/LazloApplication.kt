package com.evan.lazlo

import android.app.Application

/**
 * No first-party backend, no analytics SDK, no crash reporter — this
 * class exists only so the manifest has an android:name to point at;
 * there is deliberately nothing to initialize here that isn't already
 * lazy (Settings/SecretStore construct on first use, see core/).
 */
class LazloApplication : Application()
