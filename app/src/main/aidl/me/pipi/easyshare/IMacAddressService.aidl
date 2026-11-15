package me.pipi.easyshare;

interface IMacAddressService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    String getP2pMacAddress() = 2;
    String getMacAddressByName(String name) = 3;

    /**
     * Image files under rootDir whose lastModified is >= sinceMillis, each
     * entry encoded as "mtime|path", newest first. The walk runs with the
     * user service's shell privileges, so it can see other apps'
     * Android/data cache directories that scoped storage hides from us.
     */
    String[] listRecentImageFiles(String rootDir, long sinceMillis, int maxDepth, int maxCount) = 4;

    /** Opens one image file under shared storage for reading (shell uid). */
    android.os.ParcelFileDescriptor openImageFile(String path) = 5;
}
