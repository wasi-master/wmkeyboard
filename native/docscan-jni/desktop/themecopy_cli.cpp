// The theme copier on the desktop: reads a binary PPM, writes the theme as JSON
// to stdout, and any pictures it needs beside the given prefix as PPMs. See
// tools/themecopy/, which drives this over folders of pictures and scores it.
//
//   themecopy_cli in.ppm out_prefix [width_dp]
#include <opencv2/imgproc.hpp>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>

#include "../themecopy.hpp"

namespace {

cv::Mat readPpm(const char* path) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int w = 0, h = 0, max = 0;
    in >> magic >> w >> h >> max;
    in.get();
    if (magic != "P6" || w <= 0 || h <= 0) return {};
    cv::Mat rgb(h, w, CV_8UC3);
    in.read(reinterpret_cast<char*>(rgb.data), static_cast<std::streamsize>(rgb.total() * 3));
    cv::Mat rgba;
    cv::cvtColor(rgb, rgba, cv::COLOR_RGB2RGBA);
    return rgba;
}

void writePpm(const std::string& path, const cv::Mat& image) {
    cv::Mat rgb;
    if (image.channels() == 4) {
        cv::cvtColor(image, rgb, cv::COLOR_RGBA2RGB);
    } else {
        rgb = image;
    }
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << rgb.cols << ' ' << rgb.rows << "\n255\n";
    for (int y = 0; y < rgb.rows; ++y) out.write(rgb.ptr<char>(y), rgb.cols * 3);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "usage: themecopy_cli in.ppm out_prefix [width_dp]\n";
        return 2;
    }
    const cv::Mat rgba = readPpm(argv[1]);
    if (rgba.empty()) {
        std::cerr << "cannot read " << argv[1] << '\n';
        return 2;
    }
    const float widthDp = argc > 3 ? static_cast<float>(std::atof(argv[3])) : 360.f;
    wmdocscan::ThemeCopy copy;
    try {
        copy = wmdocscan::copyTheme(rgba, widthDp, true);
    } catch (const cv::Exception& e) {
        std::cerr << e.what() << '\n';
        return 3;
    }
    if (!copy.found) {
        std::cout << "{}\n";
        return 1;
    }
    std::cout << copy.json << '\n';
    const std::string prefix = argv[2];
    writePpm(prefix + "_overlay.ppm", copy.overlay);
    writePpm(prefix + "_redrawn.ppm", copy.redrawn);
    for (const auto& [name, image] : copy.images) writePpm(prefix + "_" + name + ".ppm", image);
    return 0;
}
