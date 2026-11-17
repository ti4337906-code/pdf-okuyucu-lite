# Keep Android 15 PdfRenderer reflection methods intact
-keepclassmembers class android.graphics.pdf.PdfRenderer$Page { *; }
-keep class android.graphics.pdf.models.** { *; }
