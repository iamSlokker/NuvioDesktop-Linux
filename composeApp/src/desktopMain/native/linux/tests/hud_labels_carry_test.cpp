// hud_labels_carry.h: pulls the HUD's label map out of a controls payload. Pure strings.
#include "hud_labels_carry.h"
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

void testExtraction() {
    require(linuxHudLabelsObject(R"({"hudLabels":{"a":"Voltar","b":"Fechar"},"isPlaying":true})")
                == R"({"a":"Voltar","b":"Fechar"})", "plain object");
    require(linuxHudLabelsObject(R"({"hudLabels":{},"x":1})") == "{}", "empty object");
    require(linuxHudLabelsObject(R"({"hudLabels":{"a":"x}y{","b":"say \"hi}\"","c":"back\\"},"z":{"k":1}})")
                == R"({"a":"x}y{","b":"say \"hi}\"","c":"back\\"})", "braces and escapes inside strings");
    require(linuxHudLabelsObject(R"({"hudLabels":{"n":{"deep":"1"}},"z":2})") == R"({"n":{"deep":"1"}})", "nested object");
}

void testNoLabels() {
    require(linuxHudLabelsObject("").empty(), "empty payload");
    require(linuxHudLabelsObject(R"({"isPlaying":true,"hudLabels":{"a":"b"}})").empty(), "labels not first");
    require(linuxHudLabelsObject(R"({"hudLabels":null,"x":1})").empty(), "labels not an object");
    require(linuxHudLabelsObject(R"({"hudLabels":{"a":"unterminated)").empty(), "truncated payload");
    require(linuxHudLabelsObject(R"({"hudLabels":{"a":"b")").empty(), "unclosed object");
}

}  // namespace

int main() {
    try {
        testExtraction();
        testNoLabels();
    } catch (const std::exception &error) {
        std::cerr << "hud labels carry test failed: " << error.what() << '\n';
        return 1;
    }
    std::cout << "HUD_LABELS_CARRY_PASS\n";
    return 0;
}
