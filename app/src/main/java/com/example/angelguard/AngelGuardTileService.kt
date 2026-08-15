import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.Manifest
import androidx.core.app.ActivityCompat
import android.service.quicksettings.TileService
fun initiateEmergencyCall(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_CALL)
    intent.data = Uri.parse("tel:$phoneNumber")
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

    // Ensure permission is granted before calling
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
        context.startActivity(intent)
    }
}