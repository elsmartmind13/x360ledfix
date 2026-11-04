package ru.alisa.x360ledfix;

/**
 * Маленькая нативная прослойка: возвращает отцепленный ядром-драйвером
 * USB-интерфейс системе (ioctl USBDEVFS_CONNECT) после того,
 * как приложение принудительно заняло интерфейс для отправки LED-команды.
 */
public final class UsbFixer {

    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("x360ledfix");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private UsbFixer() { }

    /**
     * Просит ядро заново привязать подходящие драйверы к интерфейсу.
     * Безопасно, даже если никакой драйвер ранее не был привязан.
     */
    public static int reattachInterface(int fd, int interfaceNumber) {
        if (!loaded) return -1;
        return nativeReattach(fd, interfaceNumber);
    }

    private static native int nativeReattach(int fd, int ifnum);
}
