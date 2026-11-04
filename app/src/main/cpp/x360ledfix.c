#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

/*
 * Повторно привязывает ядерные драйверы к USB-интерфейсу
 * (эквивалент libusb_attach_kernel_driver).
 */
JNIEXPORT jint JNICALL
Java_ru_alisa_x360ledfix_UsbFixer_nativeReattach(JNIEnv *env, jclass clazz, jint fd, jint ifnum)
{
    unsigned int n = (unsigned int) ifnum;
    int r = ioctl((int) fd, USBDEVFS_CONNECT, &n);
    return (jint) r;
}
