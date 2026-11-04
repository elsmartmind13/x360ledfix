package ru.alisa.x360ledfix;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

/**
 * X360 LED Fix — гасит мигание всех четырёх индикаторов геймпада Xbox 360,
 * подключённого через фирменный беспроводной ресивер к хостам без
 * XInput-драйвера (Google TV / Android TV).
 *
 * Прошивка телевизора видит ресивер как обычное USB-HID-устройство и
 * никогда не отправляет геймпаду команду «назначить квадрант» (Set LED).
 * Без неё контроллер держит состояние «все четыре индикатора мигают».
 * Приложение отправляет эту команду само, тем же пакетом, что и драйвер
 * xpad ядра Linux: 01 00 08 (0x40 + код) 00 00 00 00 00 00 00 00.
 */
public class MainActivity extends Activity {

    private static final int VENDOR_ID  = 0x045E; // Microsoft
    private static final int PRODUCT_ID = 0x0719; // Xbox 360 Wireless Receiver for Windows
    private static final String ACTION_USB_PERMISSION = "ru.alisa.x360ledfix.USB_PERMISSION";

    // Коды LED — те же, что использует драйвер xpad ядра Linux
    static final int LED_OFF       = 0;  // все погашены
    static final int LED_ALL_BLINK = 1;  // мигают все
    static final int LED_PLAYER1   = 6;  // постоянно горит квадрант 1
    static final int LED_PLAYER2   = 7;
    static final int LED_PLAYER3   = 8;
    static final int LED_PLAYER4   = 9;
    static final int LED_ROTATE    = 10; // бегущий огонёк

    private UsbManager usb;
    private TextView status;
    private CheckBox autoRepeat;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int lastCode = LED_PLAYER1;
    private volatile boolean autoRunning;
    private Thread autoThread;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            switch (action) {
                case ACTION_USB_PERMISSION:
                    boolean granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted && device != null && isReceiver(device)) {
                        postStatus("Доступ разрешён. Отправляю команду…");
                        sendLed(lastCode);
                    } else {
                        postStatus("Разрешение на доступ к ресиверу не выдано.");
                    }
                    break;
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    postStatus("Ресивер подключён.");
                    if (autoRepeat.isChecked()) requestPermission();
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    postStatus("Ресивер отключён.");
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        status = (TextView) findViewById(R.id.status);
        autoRepeat = (CheckBox) findViewById(R.id.autoRepeat);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        bindLedButton(R.id.btnOff,    LED_OFF);
        bindLedButton(R.id.btnP1,     LED_PLAYER1);
        bindLedButton(R.id.btnP2,     LED_PLAYER2);
        bindLedButton(R.id.btnP3,     LED_PLAYER3);
        bindLedButton(R.id.btnP4,     LED_PLAYER4);
        bindLedButton(R.id.btnBlink,  LED_ALL_BLINK);
        bindLedButton(R.id.btnRotate, LED_ROTATE);

        autoRepeat.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                startAuto();
                sendLed(lastCode);
            } else {
                stopAuto();
            }
        });

        postStatus(findReceiver() == null
                ? "Ресивер не найден — вставьте его в USB-порт телевизора."
                : "Ресивер найден. Нажмите кнопку, чтобы установить индикатор.");
    }

    private void bindLedButton(int id, int code) {
        Button b = (Button) findViewById(id);
        b.setOnClickListener(v -> {
            lastCode = code;
            sendLed(code);
        });
    }

    private boolean isReceiver(UsbDevice d) {
        return d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID;
    }

    private UsbDevice findReceiver() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (isReceiver(d)) return d;
        }
        return null;
    }

    private void postStatus(final String s) {
        ui.post(() -> status.setText(s));
    }

    private void requestPermission() {
        UsbDevice dev = findReceiver();
        if (dev == null) {
            postStatus("Ресивер не найден — вставьте его в USB-порт.");
            return;
        }
        if (usb.hasPermission(dev)) {
            sendLed(lastCode);
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = 0;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usb.requestPermission(dev, pi);
        postStatus("Подтвердите доступ к USB-устройству во всплывающем окне…");
    }

    private void sendLed(final int code) {
        new Thread(() -> {
            final String result = sendLedBlocking(code);
            postStatus(result);
        }, "x360-led").start();
    }

    private String sendLedBlocking(int code) {
        UsbDevice dev = findReceiver();
        if (dev == null) {
            return "Ресивер не найден — вставьте его в USB-порт телевизора.";
        }
        if (!usb.hasPermission(dev)) {
            requestPermission();
            return "Запрашиваю разрешение на доступ к ресиверу…";
        }

        UsbDeviceConnection conn = usb.openDevice(dev);
        if (conn == null) return "Не удалось открыть USB-устройство.";

        // Пакет LED-команды для беспроводного ресивера (как в драйвере xpad):
        // 01 00 08 (0x40 + код) 00 00 00 00 00 00 00 00
        byte[] packet = new byte[12];
        packet[0] = 0x01;
        packet[1] = 0x00;
        packet[2] = 0x08;
        packet[3] = (byte) (0x40 + (code & 0x0F));

        StringBuilder log = new StringBuilder();
        int ok = 0;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            UsbEndpoint epOut = findOutEndpoint(intf);
            if (epOut == null) continue;

            // 1) пробуем занять интерфейс тихо;
            // 2) если занят ядерным драйвером — принудительно (force=true).
            boolean wasKernelDriver = false;
            boolean claimed = conn.claimInterface(intf, false);
            if (!claimed) {
                claimed = conn.claimInterface(intf, true);
                wasKernelDriver = claimed;
            }
            if (!claimed) {
                log.append("инт.").append(i).append(": занята; ");
                continue;
            }
            int r = conn.bulkTransfer(epOut, packet, packet.length, 2000);
            log.append("инт.").append(i).append(r == packet.length ? ": ок" : ": err " + r).append("; ");
            if (r == packet.length) ok++;
            conn.releaseInterface(intf);
            // Вернём отцепленный ядерный драйвер, чтобы кнопки не отвалились
            if (wasKernelDriver) {
                try {
                    UsbFixer.reattachInterface(conn.getFileDescriptor(), i);
                } catch (Throwable ignored) { }
            }
        }
        conn.close();

        if (ok > 0) {
            return "Команда LED (код " + code + ") отправлена в " + ok
                    + " интерфейс(ов). Индикаторы должны перестать мигать.";
        }
        return "Не удалось отправить команду (" + log + "). "
                + "Если интерфейс занят системным сервисом — вытащите ресивер и вставьте обратно, "
                + "затем нажмите кнопку сразу после появления статуса «Ресивер подключён».";
    }

    private UsbEndpoint findOutEndpoint(UsbInterface intf) {
        for (int j = 0; j < intf.getEndpointCount(); j++) {
            UsbEndpoint e = intf.getEndpoint(j);
            if (e.getDirection() == UsbConstants.USB_DIR_OUT) return e;
        }
        return null;
    }

    private void startAuto() {
        if (autoRunning) return;
        autoRunning = true;
        autoThread = new Thread(() -> {
            while (autoRunning) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
                UsbDevice dev = findReceiver();
                if (dev != null && usb.hasPermission(dev)) sendLed(lastCode);
            }
        }, "x360-autorepeat");
        autoThread.start();
    }

    private void stopAuto() {
        autoRunning = false;
        if (autoThread != null) autoThread.interrupt();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAuto();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoRepeat.isChecked()) startAuto();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAuto();
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) { }
    }
}
