// Copies a keyboard theme out of a screenshot: finds the key grid, measures
// every surface it can see, and writes the result as a ThemeSpec in JSON.
// Shared by the Android JNI bridge (wm_docscan_jni.cpp) and the desktop tool
// (desktop/themecopy_cli.cpp), so what the Mac scores is what the phone runs.
//
// Images are 8-bit RGBA, the byte order of an Android ARGB_8888 bitmap's pixels.
#pragma once

#include <opencv2/core.hpp>

#include <string>
#include <utility>
#include <vector>

namespace wmdocscan {

struct ThemeCopy {
    // False when no key grid showed up; the rest is then empty.
    bool found = false;
    // JSON a ThemeCodec decodes as a ThemeSpec. The measurements a theme has no
    // field for ride along under "_copy", which a decoder ignores.
    std::string json;
    // Pictures the theme needs, RGBA, named by the ThemeSpec field they fill.
    std::vector<std::pair<std::string, cv::Mat>> images;
    // How closely the theme redrawn over the grid matches the picture, 0..1.
    float confidence = 0.f;
    // The picture with what was found drawn on it, only when debug is asked for.
    cv::Mat overlay;
    // The theme redrawn over the grid, only when debug is asked for.
    cv::Mat redrawn;
};

// widthDp is the width of the screen the theme will be used on: sizes come out
// of a picture as proportions of its key grid, and dp only on that screen.
ThemeCopy copyTheme(const cv::Mat& rgba, float widthDp, bool debug = false);

}  // namespace wmdocscan
