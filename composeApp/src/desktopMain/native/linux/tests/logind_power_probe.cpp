// Exercises the logind calls with no JVM; tests/logind_power_test.py provides the fake logind.
#include <jni.h>
#include <iostream>

extern "C" jboolean Java_com_nuvio_app_features_screensaver_LinuxPowerNative_canPowerOff(JNIEnv *, jobject);
extern "C" jboolean Java_com_nuvio_app_features_screensaver_LinuxPowerNative_powerOff(JNIEnv *, jobject);

int main() {
    std::cout << (Java_com_nuvio_app_features_screensaver_LinuxPowerNative_canPowerOff(nullptr, nullptr) ? "can" : "cannot") << std::endl;
    std::cout << (Java_com_nuvio_app_features_screensaver_LinuxPowerNative_powerOff(nullptr, nullptr) ? "issued" : "refused") << std::endl;
}
