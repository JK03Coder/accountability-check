package nz.co.accountabilitycheck

import android.app.Application
import nz.co.accountabilitycheck.data.AppRepository

class AccountabilityApp : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
}
