// Minimal serial port for talking to the Mabu motor board on /dev/ttyS1.
// Just open + termios baud/8N1/raw + write + close. Reads aren't needed for
// the current motor protocol (motor board doesn't reply meaningfully on its
// own, and we don't poll status).

#include <jni.h>
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "MabuSerial"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

static speed_t baud_to_speed(int baud) {
    switch (baud) {
        case   9600: return B9600;
        case  19200: return B19200;
        case  38400: return B38400;
        case  57600: return B57600;
        case 115200: return B115200;
        default:     return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_mabu_anima_SerialPort_openTty(JNIEnv* env, jclass cls,
                                              jstring jpath, jint baud) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDWR | O_NOCTTY);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) {
        LOGE("open failed: %s", strerror(errno));
        return -errno;
    }

    speed_t s = baud_to_speed(baud);
    if (s == 0) { close(fd); return -1; }

    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) {
        LOGE("tcgetattr failed: %s", strerror(errno));
        close(fd); return -errno;
    }

    cfsetispeed(&tio, s);
    cfsetospeed(&tio, s);

    // 8N1, no flow control, raw I/O. CLOCAL ignores modem status; CREAD enables
    // the receiver (we don't read but enable anyway so write isn't gated).
    tio.c_cflag = (tio.c_cflag & ~(CSIZE | PARENB | CSTOPB | CRTSCTS)) |
                  CS8 | CLOCAL | CREAD;
    tio.c_iflag = 0;
    tio.c_oflag = 0;
    tio.c_lflag = 0;
    tio.c_cc[VMIN]  = 0;
    tio.c_cc[VTIME] = 1; // 100 ms read timeout (unused for now)

    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        LOGE("tcsetattr failed: %s", strerror(errno));
        close(fd); return -errno;
    }

    tcflush(fd, TCIOFLUSH);
    LOGI("opened %d baud, fd=%d", baud, fd);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_mabu_anima_SerialPort_writeBytes(JNIEnv* env, jclass cls,
                                                 jint fd, jbyteArray data,
                                                 jint off, jint len) {
    if (fd < 0) return -1;
    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    ssize_t n = write(fd, (char*)buf + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    if (n < 0) {
        LOGE("write failed: %s", strerror(errno));
        return -errno;
    }
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_mabu_anima_SerialPort_closeTty(JNIEnv* env, jclass cls, jint fd) {
    if (fd >= 0) close(fd);
}
