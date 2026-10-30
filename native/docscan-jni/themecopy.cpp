// See themecopy.hpp and README.md, "Theme copier", for how this works. Every
// length here is relative to the key grid the picture turned out to have and no
// constant is in pixels, so a tablet, a landscape shot and a crop go down the
// same path as a phone.
#include "themecopy.hpp"

#include <opencv2/geometry.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <map>
#include <numeric>
#include <sstream>

namespace wmdocscan {
namespace {

using Colour = cv::Vec3f;  // RGB 0..255

constexpr int kMaxWidth = 1440;

float clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }

template <typename T>
T medianOf(std::vector<T> v) {
    if (v.empty()) return T();
    std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
    return v[v.size() / 2];
}

Colour medianColour(const std::vector<Colour>& v) {
    std::vector<float> c[3];
    for (const auto& p : v) for (int i = 0; i < 3; ++i) c[i].push_back(p[i]);
    return {medianOf(c[0]), medianOf(c[1]), medianOf(c[2])};
}

cv::Vec3f toLab(const Colour& c) {
    cv::Mat in(1, 1, CV_32FC3, cv::Scalar(c[0] / 255.f, c[1] / 255.f, c[2] / 255.f)), out;
    cv::cvtColor(in, out, cv::COLOR_RGB2Lab);
    return out.at<cv::Vec3f>(0, 0);
}

// Perceptual distance; 2.3 is about the least an eye can tell apart.
float dE(const Colour& a, const Colour& b) { return static_cast<float>(cv::norm(toLab(a) - toLab(b))); }

float luminance(const Colour& c) { return (0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]) / 255.f; }

float saturation(const Colour& c) {
    const float hi = std::max({c[0], c[1], c[2]}), lo = std::min({c[0], c[1], c[2]});
    return hi < 1.f ? 0.f : (hi - lo) / hi;
}

cv::Rect clipped(cv::Rect r, const cv::Size& s) { return r & cv::Rect(0, 0, s.width, s.height); }

// The commonest colour under the mask, which is what a surface with a few
// glyphs and a little noise on it is. Mean of the winning cell, so it is not
// quantised.
Colour modeColour(const cv::Mat& rgb, const cv::Mat& mask = cv::Mat()) {
    std::vector<int> count(32768, 0);
    std::vector<std::array<float, 3>> sum(32768, std::array<float, 3>{0, 0, 0});
    for (int y = 0; y < rgb.rows; ++y) {
        const auto* p = rgb.ptr<cv::Vec3b>(y);
        const uint8_t* m = mask.empty() ? nullptr : mask.ptr<uint8_t>(y);
        for (int x = 0; x < rgb.cols; ++x) {
            if (m && !m[x]) continue;
            const int cell = (p[x][0] >> 3) << 10 | (p[x][1] >> 3) << 5 | (p[x][2] >> 3);
            ++count[cell];
            for (int i = 0; i < 3; ++i) sum[cell][i] += p[x][i];
        }
    }
    const int best = static_cast<int>(std::max_element(count.begin(), count.end()) - count.begin());
    if (count[best] == 0) return {0, 0, 0};
    return {sum[best][0] / count[best], sum[best][1] / count[best], sum[best][2] / count[best]};
}

// The colour of what is drawn on a surface: the pixels furthest from it. Their
// median, not their extreme, because the very furthest are JPEG ringing.
Colour inkColour(const cv::Mat& rgb, const Colour& face, float* strength = nullptr, const cv::Mat& mask = cv::Mat()) {
    const cv::Vec3f f = toLab(face);
    cv::Mat lab;
    rgb.convertTo(lab, CV_32F, 1 / 255.0);
    cv::cvtColor(lab, lab, cv::COLOR_RGB2Lab);
    std::vector<std::pair<float, cv::Point>> far;
    far.reserve(rgb.total());
    for (int y = 0; y < lab.rows; ++y) {
        const auto* p = lab.ptr<cv::Vec3f>(y);
        const uint8_t* m = mask.empty() ? nullptr : mask.ptr<uint8_t>(y);
        for (int x = 0; x < lab.cols; ++x) {
            if (m && !m[x]) continue;
            far.push_back({static_cast<float>(cv::norm(p[x] - f)), {x, y}});
        }
    }
    if (far.empty()) return face;
    // A thin glyph covers little of a key, so a fixed share of the pixels would
    // take in its anti-aliased edge, and a share of the strongest does not.
    const size_t top = std::max<size_t>(1, far.size() / 200);
    std::nth_element(far.begin(), far.begin() + top, far.end(),
                     [](const auto& a, const auto& b) { return a.first > b.first; });
    const float strongest = far[top].first;
    std::vector<Colour> ink;
    for (const auto& f : far) {
        if (f.first < strongest * 0.72f) continue;
        const auto v = rgb.at<cv::Vec3b>(f.second);
        ink.push_back({static_cast<float>(v[0]), static_cast<float>(v[1]), static_cast<float>(v[2])});
    }
    if (strength) *strength = strongest;
    return ink.empty() ? face : medianColour(ink);
}

// ---- Finding the grid ----------------------------------------------------

struct Blob {
    float cx, cy, w, h;
};

struct Row {
    float y = 0, pitch = 0, x0 = 0, height = 0;  // height is the blobs' median
    int filled = 0;  // how many of its positions something actually sat on
    std::vector<float> xs;
};

struct Grid {
    std::vector<Row> rows;
    float pitch = 0, rowPitch = 0;
    const char* method = "";
    int score = 0;
};

// Everything drawn smaller than a key, in either polarity, per colour channel:
// a pale label on a dark key, a dark one on a pale key, a red one on a blue key
// of the same brightness. A key is wider than the kernel, so it survives the
// opening and only what sits on it comes out.
std::vector<Blob> labelBlobs(const cv::Mat& rgb) {
    const int W = rgb.cols;
    const int size = (W / 14) | 1;
    const cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, {size, size});
    std::vector<cv::Mat> channels;
    cv::split(rgb, channels);
    std::vector<Blob> blobs;
    for (const int op : {cv::MORPH_TOPHAT, cv::MORPH_BLACKHAT}) {
        cv::Mat strongest;
        for (const auto& c : channels) {
            cv::Mat one;
            cv::morphologyEx(c, one, op, kernel);
            strongest = strongest.empty() ? one : cv::max(strongest, one);
        }
        // Several levels rather than one Otsu: a gap darker than a label is dim
        // would take the threshold with it, and the label with that.
        for (const int level : {110, 60, 30, 14}) {
        cv::Mat bin;
        cv::threshold(strongest, bin, level, 255, cv::THRESH_BINARY);
        cv::Mat labels, stats, centroids;
        const int n = cv::connectedComponentsWithStats(bin, labels, stats, centroids, 8, CV_32S);
        std::vector<Blob> found;
        for (int i = 1; i < n; ++i) {
            const int w = stats.at<int>(i, cv::CC_STAT_WIDTH), h = stats.at<int>(i, cv::CC_STAT_HEIGHT);
            const int area = stats.at<int>(i, cv::CC_STAT_AREA);
            // Not the slivers and strips a key's edge or the gap network falls into.
            if (h < W * 0.006f || h > W * 0.11f || w > W * 0.11f || w > h * 2.f || w * 7 < h) continue;
            if (area < w * h * 0.1f) continue;
            found.push_back({stats.at<int>(i, cv::CC_STAT_LEFT) + w / 2.f, stats.at<int>(i, cv::CC_STAT_TOP) + h / 2.f,
                             static_cast<float>(w), static_cast<float>(h)});
        }
        // The dot of an i and the stem under it are one label.
        for (size_t a = 0; a < found.size(); ++a) {
            for (size_t b = a + 1; b < found.size(); ++b) {
                auto& p = found[a];
                const auto& q = found[b];
                if (std::abs(p.cx - q.cx) > std::max(p.w, q.w) * 0.45f) continue;
                const float top = std::min(p.cy - p.h / 2, q.cy - q.h / 2);
                const float bottom = std::max(p.cy + p.h / 2, q.cy + q.h / 2);
                const float between = bottom - top - p.h - q.h;
                if (between > std::max(p.h, q.h) * 0.8f || between < -1) continue;
                const float left = std::min(p.cx - p.w / 2, q.cx - q.w / 2);
                const float right = std::max(p.cx + p.w / 2, q.cx + q.w / 2);
                p = {(left + right) / 2, (top + bottom) / 2, right - left, bottom - top};
                found.erase(found.begin() + static_cast<long>(b));
                --b;
            }
        }
        for (const auto& b : found) {
            bool seen = false;
            for (auto& o : blobs) {
                if (std::abs(o.cx - b.cx) < std::max(o.w, b.w) * 0.5f && std::abs(o.cy - b.cy) < std::max(o.h, b.h) * 0.5f) {
                    // A lower level shows more of a glyph, up to where it joins up
                    // with the key's edge and stops being a glyph.
                    if (b.h > o.h && b.h < o.h * 1.5f && b.w < o.w * 1.5f) o = b;
                    seen = true;
                    break;
                }
            }
            if (!seen) blobs.push_back(b);
        }
        }
    }
    return blobs;
}

// Keys themselves as closed outlines: for a board whose labels drown in a photo
// or are icons. Coarser than the labels, and the fallback for that reason.
std::vector<Blob> keyBlobs(const cv::Mat& rgb) {
    const int W = rgb.cols;
    cv::Mat grey, edges;
    cv::cvtColor(rgb, grey, cv::COLOR_RGB2GRAY);
    cv::GaussianBlur(grey, grey, {3, 3}, 0);
    cv::Canny(grey, edges, 12, 40);
    cv::morphologyEx(edges, edges, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_RECT, {3, 3}));
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    std::vector<Blob> blobs;
    for (const auto& c : contours) {
        const cv::Rect r = cv::boundingRect(c);
        if (r.width < W / 22 || r.width > W / 6 || r.height < r.width * 0.55f || r.height > r.width * 2.4f) continue;
        if (cv::contourArea(c) < r.area() * 0.6f) continue;
        const Blob b{r.x + r.width / 2.f, r.y + r.height / 2.f, static_cast<float>(r.width), static_cast<float>(r.height)};
        bool duplicate = false;
        for (const auto& o : blobs) {
            if (std::abs(o.cx - b.cx) < b.w * 0.3f && std::abs(o.cy - b.cy) < b.h * 0.3f) duplicate = true;
        }
        if (!duplicate) blobs.push_back(b);
    }
    return blobs;
}

// Rows of evenly spaced things, one pitch for them all, one above the other.
Grid gridFrom(std::vector<Blob> blobs, int W, const char* method, float closeRows) {
    Grid grid;
    grid.method = method;
    if (blobs.size() < 12) return grid;
    // The pitch first, from every pair of things side by side. It is what the
    // whole board votes for, so noise and a label split in two do not move it.
    const int cells = 400;
    std::vector<float> votes(cells + 2, 0.f);
    for (size_t i = 0; i < blobs.size(); ++i) {
        for (size_t j = i + 1; j < blobs.size(); ++j) {
            const auto& a = blobs[i];
            const auto& b = blobs[j];
            const float d = std::abs(a.cx - b.cx);
            if (std::abs(a.cy - b.cy) > std::min(a.h, b.h) * 0.5f || d < W / 20.f || d > W / 5.f) continue;
            votes[static_cast<int>(d * cells / W)] += 1.f;
        }
    }
    int peak = 0;
    float peakVotes = 0;
    for (int i = 1; i < cells; ++i) {
        const float v = votes[i - 1] + votes[i] + votes[i + 1];
        if (v > peakVotes) { peakVotes = v; peak = i; }
    }
    if (peakVotes < 6) return grid;
    float pitch = 0, weight = 0;
    for (int i = std::max(0, peak - 2); i <= peak + 2; ++i) { pitch += votes[i] * (i + 0.5f); weight += votes[i]; }
    pitch = pitch / weight * W / cells;

    // Rows: the blob with the most neighbours on its comb starts one, and takes
    // whatever else lies on that comb at its height.
    const auto onComb = [&](const Blob& a, const Blob& b) {
        const float t = std::abs(a.cx - b.cx) / pitch;
        return std::abs(t - std::round(t)) < 0.16f && std::abs(a.cy - b.cy) < std::max(a.h, b.h) * 0.42f &&
               b.h > a.h * 0.5f && b.h < a.h * 2.f;
    };
    std::vector<uint8_t> used(blobs.size(), 0);
    std::vector<Row> rows;
    for (;;) {
        int seed = -1, seedCount = 0;
        for (size_t i = 0; i < blobs.size(); ++i) {
            if (used[i]) continue;
            int count = 0;
            for (size_t j = 0; j < blobs.size(); ++j) {
                if (!used[j] && onComb(blobs[i], blobs[j])) ++count;
            }
            if (count > seedCount) { seedCount = count; seed = static_cast<int>(i); }
        }
        if (seed < 0 || seedCount < 4) break;
        std::vector<Blob> members;
        for (size_t j = 0; j < blobs.size(); ++j) {
            if (!used[j] && onComb(blobs[seed], blobs[j])) { members.push_back(blobs[j]); used[j] = 1; }
        }
        std::sort(members.begin(), members.end(), [](const Blob& a, const Blob& b) { return a.cx < b.cx; });
        Row row;
        row.pitch = pitch;
        std::vector<float> ys, hs;
        for (const auto& m : members) {
            if (!row.xs.empty() && m.cx - row.xs.back() < pitch * 0.5f) continue;
            row.xs.push_back(m.cx);
            ys.push_back(m.cy);
            hs.push_back(m.h);
        }
        // Letters fill their comb. The row under them and a toolbar that happens
        // to fall on it have holes.
        const float span = (row.xs.back() - row.xs.front()) / pitch + 1;
        if (row.xs.size() < 5 || row.xs.size() < span * 0.7f) continue;
        row.y = medianOf(ys);
        row.height = medianOf(hs);
        row.filled = static_cast<int>(row.xs.size());
        rows.push_back(row);
    }
    if (rows.size() < 3) return grid;
    {
        // Fragments of key corners can line up too, at a small size.
        // And a row of tool backgrounds, at a large one.
        std::vector<float> heights;
        for (const auto& r : rows) heights.push_back(r.height);
        const float typical = medianOf(heights);
        rows.erase(std::remove_if(rows.begin(), rows.end(), [&](const Row& r) {
            return r.height < typical * 0.5f || r.height > typical * 1.6f;
        }), rows.end());
    }
    if (rows.size() < 3) return grid;
    // Corner hints line up into a row of their own, smaller than the labels
    // under them and close above them. Key outlines have no such thing, and
    // are as tall as their row pitch, hence the caller's distance.
    std::vector<Row> kept;
    std::vector<size_t> order(rows.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return rows[a].height * rows[a].xs.size() > rows[b].height * rows[b].xs.size();
    });
    for (const size_t i : order) {
        bool close = false;
        for (const auto& k : kept) close |= std::abs(k.y - rows[i].y) < std::max(k.height, rows[i].height) * closeRows;
        if (!close) kept.push_back(rows[i]);
    }
    std::sort(kept.begin(), kept.end(), [](const Row& a, const Row& b) { return a.y < b.y; });
    if (kept.size() < 3) return grid;
    std::vector<float> dy;
    for (size_t i = 1; i < kept.size(); ++i) dy.push_back(kept[i].y - kept[i - 1].y);
    const float rowPitch = medianOf(dy);
    // The longest stack of rows a pitch apart, counted by the labels in it.
    size_t bestStart = 0, bestEnd = 0, bestCount = 0;
    for (size_t s = 0; s < kept.size(); ++s) {
        size_t e = s, count = kept[s].xs.size();
        while (e + 1 < kept.size() && kept[e + 1].y - kept[e].y < rowPitch * 1.4f &&
               kept[e + 1].y - kept[e].y > rowPitch * 0.65f) {
            ++e;
            count += kept[e].xs.size();
        }
        if (count > bestCount) { bestCount = count; bestStart = s; bestEnd = e; }
    }
    if (bestEnd - bestStart + 1 < 3) return grid;
    grid.rows.assign(kept.begin() + static_cast<long>(bestStart), kept.begin() + static_cast<long>(bestEnd) + 1);
    // Lines of text and rows of settings can stack up evenly too. A keyboard's
    // rows are nearly full, nearly as wide as the picture, and taller than their
    // labels by far more than text is.
    {
        int fullRows = 0, longest = 0, crowded = 0;
        for (const auto& r : grid.rows) {
            if (r.filled >= 5) ++fullRows;
            longest = std::max(longest, r.filled);
            // A key has a label and perhaps a hint. A line of text has a letter
            // every fraction of the pitch.
            int here = 0;
            for (const auto& b : blobs) {
                if (std::abs(b.cy - r.y) < r.height * 0.6f && b.cx > r.xs.front() - pitch / 2 && b.cx < r.xs.back() + pitch / 2) ++here;
            }
            if (here > r.filled * 2.2f) ++crowded;
        }
        const float across = longest * pitch;
        if (fullRows < 3 || longest < 7 || crowded >= 2 || across < W * 0.5f || pitch < W / 18.f || pitch > W / 7.f) {
            grid.rows.clear();
            grid.score = 0;
            return grid;
        }
    }
    grid.pitch = pitch;
    grid.rowPitch = rowPitch;
    grid.score = static_cast<int>(bestCount);
    // A row's positions as an exact comb, so one blob pulled off centre by a
    // hint or a descender does not move its key. Then the comb runs on as far
    // as anything sits on it: the longest run can stop short of the row's ends
    // at a label the clustering put elsewhere.
    for (auto& r : grid.rows) {
        float sum = 0;
        for (const float x : r.xs) sum += x - pitch * std::round((x - r.xs.front()) / pitch);
        r.x0 = sum / r.xs.size();
        const auto occupied = [&](float x) {
            for (const auto& b : blobs) {
                if (std::abs(b.cx - x) < pitch * 0.25f && std::abs(b.cy - r.y) < std::min(rowPitch * 0.3f, r.height) &&
                    b.h > r.height * 0.4f) {
                    return true;
                }
            }
            return false;
        };
        int first = 0;
        int last = static_cast<int>(std::round((r.xs.back() - r.xs.front()) / pitch));
        while (r.x0 + (first - 1) * pitch > -pitch * 0.2f && occupied(r.x0 + (first - 1) * pitch)) --first;
        while (r.x0 + (last + 1) * pitch < W + pitch * 0.2f && occupied(r.x0 + (last + 1) * pitch)) ++last;
        r.xs.clear();
        for (int i = first; i <= last; ++i) r.xs.push_back(r.x0 + i * pitch);
        r.x0 = r.xs.front();
        r.pitch = pitch;
    }
    return grid;
}

// ---- Key geometry -----------------------------------------------------------

struct KeyRect {
    cv::Rect rect;
    int row = 0, index = 0;
};

// Where a colour profile first stops looking like its own start.
int firstDeparture(const std::vector<Colour>& p, bool fromEnd, float threshold) {
    const int n = static_cast<int>(p.size());
    if (n == 0) return 0;
    const Colour start = fromEnd ? p[n - 1] : p[0];
    for (int i = 0; i < n; ++i) {
        const int at = fromEnd ? n - 1 - i : i;
        if (dE(p[at], start) > threshold) return at;
    }
    return -1;
}

Colour meanOf(const cv::Mat& rgb, cv::Rect r) {
    r = clipped(r, rgb.size());
    if (r.area() <= 0) return {0, 0, 0};
    const cv::Scalar m = cv::mean(rgb(r));
    return {static_cast<float>(m[0]), static_cast<float>(m[1]), static_cast<float>(m[2])};
}

// Colour per y over several bands, median across them, so a label that crosses
// one band loses the vote.
std::vector<Colour> profileY(const cv::Mat& rgb, const std::vector<cv::Rect>& bands, int y0, int y1) {
    std::vector<Colour> out;
    for (int y = y0; y < y1; ++y) {
        std::vector<Colour> here;
        for (const auto& b : bands) here.push_back(meanOf(rgb, {b.x, y, b.width, 1}));
        out.push_back(medianColour(here));
    }
    return out;
}

std::vector<Colour> profileX(const cv::Mat& rgb, const std::vector<cv::Rect>& bands, int length) {
    std::vector<Colour> out;
    for (int t = 0; t < length; ++t) {
        std::vector<Colour> here;
        for (const auto& b : bands) here.push_back(meanOf(rgb, {b.x + t, b.y, 1, b.height}));
        out.push_back(medianColour(here));
    }
    return out;
}

// Face-to-face gap between neighbours along a profile: how far the near face
// runs and where the far one starts. Flush keys report the middle for both.
std::pair<int, int> gapIn(const std::vector<Colour>& p) {
    const int n = static_cast<int>(p.size());
    float peak = 0;
    for (const auto& c : p) peak = std::max(peak, std::min(dE(c, p[0]), dE(c, p[n - 1])));
    if (peak < 2.5f) return {n / 2, n / 2};
    const float threshold = std::max(2.f, peak * 0.3f);
    int near = firstDeparture(p, false, threshold);
    int far = firstDeparture(p, true, threshold);
    if (near < 0 || far < 0 || far < near) return {n / 2, n / 2};
    return {near, far};
}

// Outlines the renderer has, in the same proportions as ThemeRendering.kt.
enum class Shape { Rounded, Sharp, Pill, Cut, Squircle, Arch, Leaf, Slant, Hexagon, Scallop, Ticket, Circle };

const char* shapeName(Shape s) {
    switch (s) {
        case Shape::Rounded: return "ROUNDED";
        case Shape::Sharp: return "SHARP";
        case Shape::Pill: return "PILL";
        case Shape::Cut: return "CUT";
        case Shape::Squircle: return "SQUIRCLE";
        case Shape::Arch: return "ARCH";
        case Shape::Leaf: return "LEAF";
        case Shape::Slant: return "SLANT";
        case Shape::Hexagon: return "HEXAGON";
        case Shape::Scallop: return "SCALLOP";
        case Shape::Ticket: return "TICKET";
        case Shape::Circle: return "CIRCLE";
    }
    return "ROUNDED";
}

void cornerArc(std::vector<cv::Point2f>& out, float cx, float cy, float rx, float ry, float from, float to) {
    const int steps = std::max(3, static_cast<int>(std::max(rx, ry)));
    for (int i = 0; i <= steps; ++i) {
        const float t = (from + (to - from) * i / steps) * static_cast<float>(CV_PI) / 180.f;
        out.push_back({cx + rx * std::cos(t), cy + ry * std::sin(t)});
    }
}

std::vector<cv::Point2f> roundedOutline(float w, float h, float tl, float tr, float br, float bl) {
    std::vector<cv::Point2f> o;
    cornerArc(o, tl, tl, tl, tl, 180, 270);
    cornerArc(o, w - tr, tr, tr, tr, 270, 360);
    cornerArc(o, w - br, h - br, br, br, 0, 90);
    cornerArc(o, bl, h - bl, bl, bl, 90, 180);
    return o;
}

// The outline drawn into a 8-bit mask of the given size, at the given origin.
void drawShape(cv::Mat& mask, Shape shape, cv::Rect box, float radius, int value = 255) {
    const float w = static_cast<float>(box.width), h = static_cast<float>(box.height);
    const float m = std::min(w, h);
    std::vector<cv::Point2f> o;
    switch (shape) {
        case Shape::Rounded: o = roundedOutline(w, h, radius, radius, radius, radius); break;
        case Shape::Sharp: o = {{0, 0}, {w, 0}, {w, h}, {0, h}}; break;
        case Shape::Pill: o = roundedOutline(w, h, m / 2, m / 2, m / 2, m / 2); break;
        case Shape::Cut:
            o = {{radius, 0}, {w - radius, 0}, {w, radius}, {w, h - radius}, {w - radius, h}, {radius, h}, {0, h - radius}, {0, radius}};
            break;
        case Shape::Squircle:
            for (int i = 0; i < 64; ++i) {
                const float t = 2.f * static_cast<float>(CV_PI) * i / 64;
                const float c = std::cos(t), s = std::sin(t);
                o.push_back({w / 2 + w / 2 * std::copysign(std::sqrt(std::abs(c)), c),
                             h / 2 + h / 2 * std::copysign(std::sqrt(std::abs(s)), s)});
            }
            break;
        case Shape::Arch: o = roundedOutline(w, h, m / 2, m / 2, m * 0.12f, m * 0.12f); break;
        case Shape::Leaf: o = roundedOutline(w, h, m / 2, m * 0.08f, m / 2, m * 0.08f); break;
        case Shape::Slant: {
            const float lean = std::min(h * 0.22f, w * 0.3f);
            o = {{lean / 2, 0}, {w + lean / 2, 0}, {w - lean / 2, h}, {-lean / 2, h}};
            break;
        }
        case Shape::Hexagon: {
            const float cut = std::min(w * 0.16f, h * 0.5f);
            o = {{cut, 0}, {w - cut, 0}, {w, h / 2}, {w - cut, h}, {cut, h}, {0, h / 2}};
            break;
        }
        case Shape::Scallop: {
            const float d = m / 7;
            const int across = std::max(2, static_cast<int>(std::round((w - 2 * d) / (2 * d))));
            const int down = std::max(1, static_cast<int>(std::round((h - 2 * d) / (2 * d))));
            const float sx = (w - 2 * d) / across, sy = (h - 2 * d) / down;
            for (int i = 0; i < across; ++i) cornerArc(o, d + (i + 0.5f) * sx, d, sx / 2, d, 180, 360);
            for (int i = 0; i < down; ++i) cornerArc(o, w - d, d + (i + 0.5f) * sy, d, sy / 2, 270, 450);
            for (int i = across - 1; i >= 0; --i) cornerArc(o, d + (i + 0.5f) * sx, h - d, sx / 2, d, 0, 180);
            for (int i = down - 1; i >= 0; --i) cornerArc(o, d, d + (i + 0.5f) * sy, d, sy / 2, 90, 270);
            break;
        }
        case Shape::Ticket: {
            const float r = m * 0.25f;
            cornerArc(o, 0, 0, r, r, 90, 0);
            cornerArc(o, w, 0, r, r, 180, 90);
            cornerArc(o, w, h, r, r, 270, 180);
            cornerArc(o, 0, h, r, r, 360, 270);
            break;
        }
        case Shape::Circle:
            if (std::max(w, h) > m * 1.6f) {
                o = roundedOutline(w, h, m / 2, m / 2, m / 2, m / 2);
            } else {
                cornerArc(o, w / 2, h / 2, m / 2, m / 2, 0, 360);
            }
            break;
    }
    std::vector<cv::Point> points;
    for (const auto& p : o) points.push_back({cvRound(p.x + box.x), cvRound(p.y + box.y)});
    cv::fillPoly(mask, std::vector<std::vector<cv::Point>>{points}, cv::Scalar(value), cv::LINE_8);
}

struct ShapeFit {
    Shape shape = Shape::Rounded;
    float radius = 0;
    float error = 1;
};

// Which outline covers the same pixels: every kind against the mask of what is
// not board, the corner radius scanned for the two kinds that have one.
ShapeFit fitShape(const cv::Mat& raw, cv::Rect box) {
    ShapeFit best;
    const float m = static_cast<float>(std::min(box.width, box.height));
    // Only the corners decide between outlines, and the sides of a key are most
    // of its box, so the error is against the corners' area. Neighbours leaning
    // into the margin are not this key's, hence only the box and a pixel around.
    const float area = std::max(1.f, m * m);
    const cv::Rect window = clipped({box.x - 1, box.y - 1, box.width + 2, box.height + 2}, raw.size());
    cv::Mat mask = cv::Mat::zeros(raw.size(), CV_8U);
    for (int y = window.y; y < window.y + window.height; ++y) {
        const auto* in = raw.ptr<uint8_t>(y);
        auto* out = mask.ptr<uint8_t>(y);
        int first = -1, last = -1;
        for (int x = window.x; x < window.x + window.width; ++x) {
            if (in[x]) { if (first < 0) first = x; last = x; }
        }
        // A label's or texture's holes in a face are not its outline.
        for (int x = first; first >= 0 && x <= last; ++x) out[x] = 255;
    }
    const auto score = [&](Shape s, float r) {
        cv::Mat candidate = cv::Mat::zeros(mask.size(), CV_8U), diff;
        drawShape(candidate, s, box, r);
        cv::absdiff(candidate(window), mask(window), diff);
        return static_cast<float>(cv::countNonZero(diff)) / area;
    };
    for (float r = 0; r <= m / 2 + 0.5f; r += std::max(1.f, m / 64)) {
        const float e = score(Shape::Rounded, r);
        if (e < best.error) best = {Shape::Rounded, r, e};
    }
    // The plain outline wins a tie: a decorative one has to earn it.
    const float plain = best.error;
    const float earn = 0.006f;
    for (const Shape s : {Shape::Squircle, Shape::Arch, Shape::Leaf, Shape::Slant, Shape::Hexagon, Shape::Scallop, Shape::Ticket}) {
        const float e = score(s, 0);
        if (e + earn < plain && e < best.error) best = {s, 0, e};
    }
    for (float r = 2; r <= m / 2; r += std::max(1.f, m / 64)) {
        const float e = score(Shape::Cut, r);
        if (e + earn < plain && e < best.error) best = {Shape::Cut, r, e};
    }
    if (best.shape == Shape::Rounded) {
        if (best.radius < std::max(1.f, m * 0.02f)) {
            best.shape = Shape::Sharp;
        } else if (best.radius >= m * 0.46f) {
            best.shape = std::abs(box.width - box.height) < m * 0.08f ? Shape::Circle : Shape::Pill;
        }
    }
    return best;
}

// ---- Board model --------------------------------------------------------------

enum class BoardKind { Solid, Linear, Radial, Sweep, Image };

struct BoardFit {
    BoardKind kind = BoardKind::Solid;
    Colour solid{0, 0, 0};
    std::vector<Colour> stops;
    float angle = 0;
    float residual = 0;
};

struct Sample {
    float x, y;
    Colour c;
};

float axisT(BoardKind kind, float x, float y, float w, float h, float angle) {
    switch (kind) {
        case BoardKind::Linear: {
            const float rad = angle * static_cast<float>(CV_PI) / 180.f;
            const float dx = std::cos(rad), dy = std::sin(rad);
            const float half = (std::abs(dx) * w + std::abs(dy) * h) / 2;
            return clampf(((x - w / 2) * dx + (y - h / 2) * dy) / (2 * half) + 0.5f, 0, 1);
        }
        case BoardKind::Radial:
            return clampf(std::hypot(x - w / 2, y - h / 2) / (std::max(w, h) * 0.75f), 0, 1);
        case BoardKind::Sweep: {
            float a = std::atan2(y - h / 2, x - w / 2) * 180.f / static_cast<float>(CV_PI) - angle;
            a = std::fmod(a + 720.f, 360.f);
            return a / 360.f;
        }
        default: return 0;
    }
}

Colour stopsAt(const std::vector<Colour>& stops, float t, bool closed) {
    const int n = static_cast<int>(stops.size());
    const int spans = closed ? n : n - 1;
    const float at = t * spans;
    const int i = std::min(static_cast<int>(at), spans - 1);
    const float f = at - i;
    const Colour& a = stops[i];
    const Colour& b = stops[closed ? (i + 1) % n : i + 1];
    return a * (1 - f) + b * f;
}

// Least squares for the colours at evenly spaced stops, the way a shader lays
// them out. Returns the 75th percentile of the error left: a gap is a few
// pixels wide, so a good many samples touch a key's edge.
float fitStops(const std::vector<Sample>& samples, BoardKind kind, float w, float h, float angle, int count,
               std::vector<Colour>& stops) {
    const bool closed = kind == BoardKind::Sweep;
    const int spans = closed ? count : count - 1;
    cv::Mat A = cv::Mat::zeros(count, count, CV_32F), B = cv::Mat::zeros(count, 3, CV_32F);
    for (const auto& s : samples) {
        const float at = axisT(kind, s.x, s.y, w, h, angle) * spans;
        const int i = std::min(static_cast<int>(at), spans - 1);
        const int j = closed ? (i + 1) % count : i + 1;
        const float f = at - i, a = 1 - f;
        A.at<float>(i, i) += a * a;
        A.at<float>(j, j) += f * f;
        A.at<float>(i, j) += a * f;
        A.at<float>(j, i) += a * f;
        for (int c = 0; c < 3; ++c) {
            B.at<float>(i, c) += a * s.c[c];
            B.at<float>(j, c) += f * s.c[c];
        }
    }
    A += cv::Mat::eye(count, count, CV_32F) * 1e-3f;
    cv::Mat X;
    if (!cv::solve(A, B, X, cv::DECOMP_CHOLESKY)) return 1e9f;
    stops.clear();
    for (int i = 0; i < count; ++i) {
        stops.push_back({clampf(X.at<float>(i, 0), 0, 255), clampf(X.at<float>(i, 1), 0, 255), clampf(X.at<float>(i, 2), 0, 255)});
    }
    std::vector<float> errors;
    errors.reserve(samples.size());
    for (const auto& s : samples) {
        const Colour m = stopsAt(stops, axisT(kind, s.x, s.y, w, h, angle), closed);
        errors.push_back(static_cast<float>(cv::norm(m - s.c)));
    }
    std::nth_element(errors.begin(), errors.begin() + errors.size() * 3 / 4, errors.end());
    return errors[errors.size() * 3 / 4];
}

// Every way the board could have been painted, simplest first: one colour, then
// a gradient of each kind, then a picture when none of them explains the pixels.
BoardFit fitBoard(const std::vector<Sample>& samples, float w, float h) {
    BoardFit fit;
    std::vector<Colour> all;
    for (const auto& s : samples) all.push_back(s.c);
    fit.solid = medianColour(all);
    std::vector<float> errors;
    for (const auto& c : all) errors.push_back(static_cast<float>(cv::norm(c - fit.solid)));
    std::nth_element(errors.begin(), errors.begin() + errors.size() * 3 / 4, errors.end());
    fit.residual = errors[errors.size() * 3 / 4];
    if (fit.residual < 8.f) return fit;

    // The plane through the colours gives a straight gradient's direction.
    cv::Mat A(static_cast<int>(samples.size()), 3, CV_32F), Y(static_cast<int>(samples.size()), 3, CV_32F);
    for (int i = 0; i < A.rows; ++i) {
        A.at<float>(i, 0) = samples[i].x / w - 0.5f;
        A.at<float>(i, 1) = samples[i].y / h - 0.5f;
        A.at<float>(i, 2) = 1;
        for (int c = 0; c < 3; ++c) Y.at<float>(i, c) = samples[i].c[c];
    }
    cv::Mat X;
    cv::solve(A, Y, X, cv::DECOMP_SVD);
    float gx = 0, gy = 0;
    // Signed by the strongest channel, or two channels moving opposite ways
    // (blue to orange) would cancel.
    int strongest = 0;
    float most = -1;
    for (int c = 0; c < 3; ++c) {
        const float size = std::hypot(X.at<float>(0, c), X.at<float>(1, c));
        if (size > most) { most = size; strongest = c; }
    }
    gx = X.at<float>(0, strongest) / w;
    gy = X.at<float>(1, strongest) / h;
    const float planeAngle = std::atan2(gy, gx) * 180.f / static_cast<float>(CV_PI);

    float best = 1e9f;
    BoardFit candidate = fit;
    const auto consider = [&](BoardKind kind, float angle) {
        for (const int count : {2, 3, 4}) {
            std::vector<Colour> stops;
            const float e = fitStops(samples, kind, w, h, angle, count, stops);
            // A further stop has to pay for itself.
            if (e < best - 1.5f) {
                best = e;
                candidate.kind = kind;
                candidate.stops = stops;
                candidate.angle = angle;
                candidate.residual = e;
            }
        }
    };
    consider(BoardKind::Linear, planeAngle);
    for (const float snap : {0.f, 45.f, 90.f, 135.f, 180.f, 225.f, 270.f, 315.f}) {
        if (std::abs(std::remainder(snap - planeAngle, 360.f)) < 12.f) consider(BoardKind::Linear, snap);
    }
    consider(BoardKind::Radial, 0);
    consider(BoardKind::Sweep, 0);
    if (best < 12.f) return candidate;
    fit.kind = BoardKind::Image;
    return fit;
}

Colour boardAt(const BoardFit& b, float x, float y, float w, float h) {
    if (b.kind == BoardKind::Solid || b.kind == BoardKind::Image) return b.solid;
    return stopsAt(b.stops, axisT(b.kind, x, y, w, h, b.angle), b.kind == BoardKind::Sweep);
}

// Fills the holes in a picture from what is around them, coarse to fine.
cv::Mat pushPull(const cv::Mat& rgb, const cv::Mat& known) {
    std::vector<cv::Mat> colour{}, weight{};
    cv::Mat c, k;
    rgb.convertTo(c, CV_32FC3);
    known.convertTo(k, CV_32F, 1 / 255.0);
    std::vector<cv::Mat> kk{k, k, k};
    cv::Mat k3;
    cv::merge(kk, k3);
    c = c.mul(k3);
    colour.push_back(c);
    weight.push_back(k);
    while (std::min(colour.back().rows, colour.back().cols) > 2) {
        cv::Mat dc, dw;
        cv::pyrDown(colour.back(), dc);
        cv::pyrDown(weight.back(), dw);
        colour.push_back(dc);
        weight.push_back(dw);
    }
    for (int i = static_cast<int>(colour.size()) - 2; i >= 0; --i) {
        cv::Mat uc, uw;
        cv::pyrUp(colour[i + 1], uc, colour[i].size());
        cv::pyrUp(weight[i + 1], uw, weight[i].size());
        for (int y = 0; y < colour[i].rows; ++y) {
            auto* pc = colour[i].ptr<cv::Vec3f>(y);
            auto* pw = weight[i].ptr<float>(y);
            const auto* qc = uc.ptr<cv::Vec3f>(y);
            const auto* qw = uw.ptr<float>(y);
            for (int x = 0; x < colour[i].cols; ++x) {
                const float a = pw[x];
                pc[x] = pc[x] + qc[x] * (1 - a);
                pw[x] = a + qw[x] * (1 - a);
            }
        }
    }
    cv::Mat out(rgb.size(), CV_8UC3);
    for (int y = 0; y < out.rows; ++y) {
        auto* po = out.ptr<cv::Vec3b>(y);
        const auto* pc = colour[0].ptr<cv::Vec3f>(y);
        const auto* pw = weight[0].ptr<float>(y);
        for (int x = 0; x < out.cols; ++x) {
            const float w = std::max(pw[x], 1e-3f);
            for (int i = 0; i < 3; ++i) po[x][i] = cv::saturate_cast<uint8_t>(pc[x][i] / w);
        }
    }
    const cv::Mat keep = known > 127;
    rgb.copyTo(out, keep);
    return out;
}

// ---- Output ------------------------------------------------------------------

std::string argb(const Colour& c, float alpha = 1.f) {
    const uint64_t a = static_cast<uint64_t>(clampf(std::round(alpha * 255.f), 0, 255));
    const uint64_t r = static_cast<uint64_t>(clampf(std::round(c[0]), 0, 255));
    const uint64_t g = static_cast<uint64_t>(clampf(std::round(c[1]), 0, 255));
    const uint64_t b = static_cast<uint64_t>(clampf(std::round(c[2]), 0, 255));
    return std::to_string((a << 24) | (r << 16) | (g << 8) | b);
}

class Json {
public:
    void raw(const std::string& key, const std::string& value) {
        out_ << (first_ ? "" : ",") << '"' << key << "\":" << value;
        first_ = false;
    }
    void str(const std::string& key, const std::string& value) { raw(key, '"' + value + '"'); }
    void num(const std::string& key, float value) {
        char buffer[32];
        std::snprintf(buffer, sizeof(buffer), "%.3f", value);
        raw(key, buffer);
    }
    void integer(const std::string& key, int value) { raw(key, std::to_string(value)); }
    void boolean(const std::string& key, bool value) { raw(key, value ? "true" : "false"); }
    std::string done() const { return '{' + out_.str() + '}'; }

private:
    std::ostringstream out_;
    bool first_ = true;
};

std::string gradientJson(const std::vector<Colour>& stops, const char* type, float angle, float alpha = 1.f) {
    std::string colours;
    for (const auto& s : stops) colours += (colours.empty() ? "" : ",") + argb(s, alpha);
    Json j;
    j.raw("colors", '[' + colours + ']');
    j.str("type", type);
    j.num("angleDeg", angle);
    return j.done();
}

const char* boardName(BoardKind k) {
    switch (k) {
        case BoardKind::Solid: return "solid";
        case BoardKind::Linear: return "linear";
        case BoardKind::Radial: return "radial";
        case BoardKind::Sweep: return "sweep";
        case BoardKind::Image: return "image";
    }
    return "";
}

cv::Rect inset(const cv::Rect& r, float fx, float fy) {
    const int dx = static_cast<int>(r.width * fx), dy = static_cast<int>(r.height * fy);
    return {r.x + dx, r.y + dy, r.width - 2 * dx, r.height - 2 * dy};
}

// The letters a key position stands for, when a row has as many keys as QWERTY.
const char* const kQwerty[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

ThemeCopy copyWithGrid(const cv::Mat& rgba, float widthDp, bool debug, bool preferLabels) {
    ThemeCopy result;
    if (rgba.empty() || rgba.type() != CV_8UC4) return result;

    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
    if (rgb.cols > kMaxWidth) {
        cv::resize(rgb, rgb, {}, static_cast<double>(kMaxWidth) / rgb.cols, static_cast<double>(kMaxWidth) / rgb.cols, cv::INTER_AREA);
    }
    const int W = rgb.cols, H = rgb.rows;

    // Two ways to find the grid; whichever accounts for more keys. The labels
    // go first, since a stack of label rows gives the exact key centres.
    std::vector<Blob> labels = labelBlobs(rgb);
    Grid grid = gridFrom(labels, W, "labels", 1.45f);
    Grid byKeys = gridFrom(keyBlobs(rgb), W, "outlines", 0.5f);
    // Closed outlines are the surer of the two wherever they make a board, since
    // nothing but a key is that shape, and they give its edges as well.
    const float labelHeight = grid.rows.empty() ? 0.f : grid.rows[grid.rows.size() / 2].height;
    if (!preferLabels && (byKeys.score > grid.score || byKeys.score >= 20)) grid = byKeys;
    if (grid.rows.size() < 3) return result;

    const bool byOutlines = grid.method[0] == 'o';
    const float pitch = grid.pitch, rowPitch = grid.rowPitch;
    // A busy picture can cost a row its blobs' agreement, though they are still
    // there. Rows go on for as long as a pitch further on has a comb of labels.
    const auto rowAt = [&](float y, const std::vector<Blob>& blobs, float height, Row& out) {
        std::vector<float> xs;
        for (const auto& b : blobs) {
            if (std::abs(b.cy - y) < rowPitch * 0.24f && b.h > height * 0.5f && b.h < height * 1.6f) xs.push_back(b.cx);
        }
        // Phase of the comb, by vote.
        float bestPhase = 0;
        size_t bestCount = 0;
        for (const float x : xs) {
            size_t count = 0;
            for (const float o : xs) {
                const float t = (o - x) / pitch;
                if (std::abs(t - std::round(t)) < 0.18f) ++count;
            }
            if (count > bestCount) { bestCount = count; bestPhase = x; }
        }
        if (bestCount < 5) return false;
        std::vector<float> on;
        for (const float o : xs) {
            const float t = (o - bestPhase) / pitch;
            if (std::abs(t - std::round(t)) < 0.18f) on.push_back(o);
        }
        std::sort(on.begin(), on.end());
        if (on.size() < ((on.back() - on.front()) / pitch + 1) * 0.7f) return false;
        out = Row();
        out.y = y;
        out.pitch = pitch;
        out.height = height;
        out.filled = static_cast<int>(on.size());
        const int n = static_cast<int>(std::round((on.back() - on.front()) / pitch));
        for (int i = 0; i <= n; ++i) out.xs.push_back(on.front() + i * pitch);
        out.x0 = out.xs.front();
        return true;
    };
    {
        const float height = byOutlines ? (labelHeight > 0 ? labelHeight : rowPitch * 0.3f) : grid.rows[grid.rows.size() / 2].height;
        Row extra;
        while (grid.rows.size() < 6 && rowAt(grid.rows.front().y - rowPitch, labels, height, extra) &&
               extra.xs.size() >= 9) {
            grid.rows.insert(grid.rows.begin(), extra);
        }
        // Below, only while a full letter row could still be there: the last row
        // proper has few labels, on keys of its own widths.
        while (grid.rows.size() < 4 && rowAt(grid.rows.back().y + rowPitch, labels, height, extra) && extra.xs.size() >= 6) {
            grid.rows.push_back(extra);
        }
    }
    // Letters take three rows and numbers one. Any more is a toolbar on the comb.
    if (grid.rows.size() > 4) grid.rows.erase(grid.rows.begin(), grid.rows.end() - 4);
    int rowCount = static_cast<int>(grid.rows.size());

    // Edges, out from each label along the middle of its key, where a round key
    // is at its widest. One row's edge is measured down from its own labels and
    // the next row's up from its own, since the rows are offset: a column through
    // the middle of one row's key can run down a gap in the next.
    // Shift and delete have labels too, and the last row's comb runs on over them.
    // No letter key sits that near the edge there, unless the row is a full one.
    {
        std::vector<float>& last = grid.rows[rowCount - 1].xs;
        const float margin = grid.rows[0].xs.front() - pitch / 2;
        const float full = grid.rows[0].xs.back() + pitch / 2;
        if (last.size() >= 3 && last.size() < grid.rows[0].xs.size()) {
            last.erase(std::remove_if(last.begin(), last.end(), [&](float x) {
                return x < margin + pitch * 1.4f || x > full - pitch * 1.4f;
            }), last.end());
        }
    }
    std::vector<int> faceTop(rowCount, 0), faceBottom(rowCount, 0);
    const auto columnsOf = [&](const Row& r) {
        std::vector<cv::Rect> bands;
        const int width = std::max(1, cvRound(pitch * 0.08f));
        for (const float x : r.xs) bands.push_back(clipped({cvRound(x) - width / 2, 0, width, H}, rgb.size()));
        return bands;
    };
    // Past the label, or a label's own edge reads as the key's. The end the walk
    // stops at is a pitch away or the picture's edge, and is not to be trusted
    // more than the way the middle rows measure.
    const auto departure = [&](const std::vector<Colour>& p, bool fromEnd) {
        if (p.size() < 2) return -1;
        const Colour start = fromEnd ? p.back() : p.front();
        float peak = 0;
        for (const auto& c : p) peak = std::max(peak, dE(c, start));
        if (peak < 2.5f) return -1;
        return firstDeparture(p, fromEnd, std::max(2.f, peak * 0.3f));
    };
    std::vector<float> downs, ups;
    for (int r = 0; r < rowCount; ++r) {
        const Row& row = grid.rows[r];
        const auto bands = columnsOf(row);
        // An outline's blob is the whole key, and only its middle holds a label.
        const int skip = cvRound(byOutlines ? row.height * 0.2f : row.height * 0.72f);
        const int downFrom = cvRound(row.y) + skip;
        // Not as far as the next row's label, which would outshout the gap.
        const auto down = profileY(rgb, bands, std::min(downFrom, H), std::min(H, cvRound(row.y + rowPitch) - skip));
        const int d = departure(down, false);
        faceBottom[r] = d >= 0 ? downFrom + d : -1;
        const int upTo = cvRound(row.y) - skip;
        const int upFrom = std::max(0, cvRound(row.y - rowPitch) + skip);
        const auto up = profileY(rgb, bands, upFrom, std::max(upFrom, upTo));
        const int u = departure(up, true);
        faceTop[r] = u >= 0 ? upFrom + u + 1 : -1;
        if (faceBottom[r] >= 0) downs.push_back(faceBottom[r] - row.y);
        if (faceTop[r] >= 0) ups.push_back(row.y - faceTop[r]);
    }
    const float downTypical = downs.empty() ? rowPitch * 0.48f : medianOf(downs);
    const float upTypical = ups.empty() ? rowPitch * 0.48f : medianOf(ups);
    for (int r = 0; r < rowCount; ++r) {
        const Row& row = grid.rows[r];
        if (faceBottom[r] < 0 || std::abs(faceBottom[r] - row.y - downTypical) > rowPitch * 0.14f) faceBottom[r] = cvRound(row.y + downTypical);
        if (faceTop[r] < 0 || std::abs(row.y - faceTop[r] - upTypical) > rowPitch * 0.14f) faceTop[r] = cvRound(row.y - upTypical);
    }
    for (int r = 0; r < rowCount; ++r) {
        faceTop[r] = std::max(0, faceTop[r]);
        faceBottom[r] = std::min(H, faceBottom[r]);
    }
    // Keys edge to edge show no gap at all, and the two walks overlap.
    for (int r = 0; r + 1 < rowCount; ++r) {
        if (faceBottom[r] > faceTop[r + 1]) {
            const int mid = (faceBottom[r] + faceTop[r + 1]) / 2;
            faceBottom[r] = mid;
            faceTop[r + 1] = mid;
        }
    }

    // Sideways the same way, one reading for the board: keys have one width.
    std::vector<int> rights, lefts;
    for (int r = 0; r < rowCount; ++r) {
        const Row& row = grid.rows[r];
        std::vector<cv::Rect> bands;
        const int h = std::max(1, cvRound(row.height * 0.3f));
        for (size_t i = 0; i < row.xs.size(); ++i) {
            bands.push_back({cvRound(row.xs[i] + pitch * 0.22f), cvRound(row.y) - h / 2, 1, h});
        }
        const int length = cvRound(pitch * 0.56f);
        const auto p = profileX(rgb, bands, length);
        const auto gap = gapIn(p);
        rights.push_back(gap.first + cvRound(pitch * 0.22f));
        lefts.push_back(gap.second + cvRound(pitch * 0.22f));
    }
    const int rightEdge = medianOf(rights), nextLeftEdge = medianOf(lefts);
    const int gapH = std::max(0, nextLeftEdge - rightEdge);
    std::vector<KeyRect> keys;
    for (int r = 0; r < rowCount; ++r) {
        const Row& row = grid.rows[r];
        for (size_t i = 0; i < row.xs.size(); ++i) {
            const int x = cvRound(row.xs[i]);
            const int l = x + nextLeftEdge - cvRound(pitch);
            cv::Rect rect = clipped({l, faceTop[r], x + rightEdge - l, faceBottom[r] - faceTop[r]}, rgb.size());
            if (rect.area() > 0) keys.push_back({rect, r, static_cast<int>(i)});
        }
    }
    if (keys.size() < 12) return result;
    std::vector<int> gapsV;
    for (int r = 0; r + 1 < rowCount; ++r) gapsV.push_back(faceTop[r + 1] - faceBottom[r]);
    const int gapV = std::max(0, medianOf(gapsV));

    // A key's own face, the same size for all, with its margin of board. The
    // median over every key takes the labels out, since no two share a glyph.
    std::vector<int> ws, hs;
    for (const auto& k : keys) { ws.push_back(k.rect.width); hs.push_back(k.rect.height); }
    const int tw = medianOf(ws), th = medianOf(hs);
    const int mx = std::max(2, gapH / 2 + 1), my = std::max(2, gapV / 2 + 1);
    cv::Mat tile;
    {
        std::vector<cv::Mat> crops;
        for (const auto& k : keys) {
            const cv::Rect outer(k.rect.x - mx, k.rect.y - my, k.rect.width + 2 * mx, k.rect.height + 2 * my);
            if ((outer & cv::Rect(0, 0, W, H)) != outer) continue;
            cv::Mat crop;
            cv::resize(rgb(outer), crop, {tw + 2 * mx, th + 2 * my}, 0, 0, cv::INTER_LINEAR);
            crops.push_back(crop);
        }
        if (crops.size() < 8) return result;
        tile.create(th + 2 * my, tw + 2 * mx, CV_8UC3);
        std::vector<uint8_t> v(crops.size());
        for (int y = 0; y < tile.rows; ++y) {
            for (int x = 0; x < tile.cols; ++x) {
                for (int c = 0; c < 3; ++c) {
                    for (size_t i = 0; i < crops.size(); ++i) v[i] = crops[i].at<cv::Vec3b>(y, x)[c];
                    std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
                    tile.at<cv::Vec3b>(y, x)[c] = v[v.size() / 2];
                }
            }
        }
    }
    const cv::Rect faceBox(mx, my, tw, th);
    const Colour faceColour = modeColour(tile(inset(faceBox, 0.15f, 0.15f)));
    Colour gapColour;
    {
        cv::Mat ring(tile.size(), CV_8U, cv::Scalar(255));
        ring(faceBox).setTo(0);
        gapColour = modeColour(tile, ring);
    }
    const bool faceless = dE(faceColour, gapColour) < 2.5f && gapH <= 1 && gapV <= 1;

    // Border and shadow, walking out from the face along the middle of a side.
    const auto walkOut = [&](cv::Point from, cv::Point step, int limit) {
        int run = 0;
        std::vector<Colour> seen;
        for (int i = 0; i < limit; ++i) {
            const cv::Point p = from + step * i;
            if (p.x < 0 || p.y < 0 || p.x >= tile.cols || p.y >= tile.rows) break;
            const auto v = tile.at<cv::Vec3b>(p);
            const Colour c(v[0], v[1], v[2]);
            if (dE(c, gapColour) < 3.5f) break;
            ++run;
            seen.push_back(c);
        }
        return std::make_pair(run, seen.empty() ? gapColour : medianColour(seen));
    };
    const auto left = walkOut({mx - 1, my + th / 2}, {-1, 0}, mx);
    const auto right = walkOut({mx + tw, my + th / 2}, {1, 0}, mx);
    const auto upper = walkOut({mx + tw / 2, my - 1}, {0, -1}, my);
    const auto lower = walkOut({mx + tw / 2, my + th}, {0, 1}, my);
    int borderPx = 0;
    Colour borderColour = gapColour;
    if (!faceless) {
        std::vector<int> runs{left.first, right.first, upper.first};
        const int typical = medianOf(runs);
        // A one-pixel ramp is the edge's own anti-aliasing unless its colour
        // is off the line between the two surfaces.
        const Colour mid = medianColour({left.second, right.second, upper.second});
        const float offLine = std::min(dE(mid, faceColour), dE(mid, gapColour));
        const bool even = std::abs(left.first - right.first) <= 1 && std::abs(left.first - upper.first) <= 1;
        if (even && typical >= 1 && offLine > 5.f && (typical >= 2 || offLine > 9.f)) {
            borderPx = typical;
            borderColour = mid;
        }
    }
    int shadowPx = 0;
    Colour shadowColour = gapColour;
    if (lower.first >= std::max(left.first, upper.first) + 2 && dE(lower.second, gapColour) > 4.f &&
        upper.first <= std::max(1, borderPx)) {
        shadowPx = lower.first - borderPx;
        shadowColour = lower.second;
    }

    // Outline: what is not board within the box the face plus its border makes.
    cv::Rect outer(mx - borderPx, my - borderPx, tw + 2 * borderPx, th + 2 * borderPx);
    outer = clipped(outer, tile.size());
    ShapeFit shape;
    if (!faceless) {
        cv::Mat notBoard(tile.size(), CV_8U, cv::Scalar(0));
        const float edgeLevel = std::max(10.f, static_cast<float>(cv::norm(faceColour - gapColour)) * 0.4f);
        for (int y = 0; y < tile.rows; ++y) {
            for (int x = 0; x < tile.cols; ++x) {
                const auto v = tile.at<cv::Vec3b>(y, x);
                const Colour c(v[0], v[1], v[2]);
                const float toGap = static_cast<float>(cv::norm(c - gapColour));
                notBoard.at<uint8_t>(y, x) = toGap > edgeLevel ? 255 : 0;
            }
        }
        if (shadowPx > 0) notBoard.rowRange(std::min(outer.y + outer.height, notBoard.rows), notBoard.rows).setTo(0);
        shape = fitShape(notBoard, outer);
    }

    // Grid scale, in dp: the key grid fills the screen's width less its margins.
    std::vector<int> rowKeys;
    for (const auto& r : grid.rows) rowKeys.push_back(static_cast<int>(r.xs.size()));
    const int across = *std::max_element(rowKeys.begin(), rowKeys.end());
    const float gridLeft = grid.rows[0].xs.front() - pitch / 2;
    const float gridRight = W - (grid.rows[0].xs.back() + pitch / 2);

    // Segments of each row, compared with the board in the gap beside them.
    // Rows of letters also show their wide outer keys this way, and the row
    // under the letters is laid out by nothing but this.
    const auto segmentRow = [&](int top, int bottom) {
        std::vector<cv::Rect> segments;
        const int h = bottom - top;
        if (h <= 0) return segments;
        std::vector<uint8_t> faceCol(W, 0);
        const int y1 = top + std::max(1, h / 8), y2 = bottom - std::max(2, h / 6);
        const int gapY = std::max(0, top - std::max(1, gapV / 2) - 1);
        const float threshold = std::max(3.f, dE(faceColour, gapColour) * 0.35f);
        for (int x = 0; x < W; ++x) {
            const Colour board = meanOf(rgb, {x, gapY, 1, 1});
            const Colour a = meanOf(rgb, {x, y1, 1, 1}), b = meanOf(rgb, {x, y2, 1, 1});
            faceCol[x] = (dE(a, board) > threshold || dE(b, board) > threshold) ? 1 : 0;
        }
        int start = -1;
        for (int x = 0; x <= W; ++x) {
            const bool on = x < W && faceCol[x];
            if (on && start < 0) start = x;
            if (!on && start >= 0) {
                if (x - start > pitch * 0.45f) segments.push_back({start, top, x - start, h});
                start = -1;
            }
        }
        return segments;
    };

    // The row under the letters.
    const int bottomTop = faceBottom[rowCount - 1] + gapV;
    const int bottomH = std::min(th, H - bottomTop);
    const int bottomEnd = bottomTop + bottomH;
    std::vector<cv::Rect> bottomKeys;
    bool hasBottomRow = bottomH > th * 0.6f;
    if (hasBottomRow) {
        bottomKeys = segmentRow(bottomTop, bottomEnd);
        if (bottomKeys.size() < 3 || bottomKeys.size() > 9) {
            // Flush keys or a busy picture: where a keyboard puts them.
            const float unit = (W - gridLeft - gridRight) / 10.f;
            bottomKeys.clear();
            const float widths[] = {1.5f, 1.f, 5.f, 1.f, 1.5f};
            float x = gridLeft;
            for (const float kw : widths) {
                bottomKeys.push_back({cvRound(x + gapH / 2.f), bottomTop, cvRound(kw * unit - gapH), bottomH});
                x += kw * unit;
            }
        }
    }
    cv::Rect spaceKey, enterKey, modeKey;
    std::vector<cv::Rect> smallBottom;
    if (!bottomKeys.empty()) {
        enterKey = bottomKeys.back();
        modeKey = bottomKeys.front();
        // Between the two ends, and clearly the widest: when its colour is too
        // close to the board's to be seen, there is no space bar to read at all.
        for (const auto& k : bottomKeys) {
            if (k != enterKey && k != modeKey && k.width > spaceKey.width && k.width > pitch * 2.2f) spaceKey = k;
        }
        for (const auto& k : bottomKeys) {
            if (k != enterKey && k != modeKey && k != spaceKey) smallBottom.push_back(k);
        }
    }

    // The row above the bottom row carries shift and delete on either side.
    cv::Rect shiftKey, deleteKey;
    {
        const int r = rowCount - 1;
        const int y = faceTop[r], h = faceBottom[r] - y;
        const float rowLeft = grid.rows[r].xs.front() - pitch / 2 + gapH / 2.f;
        const float rowRight = grid.rows[r].xs.back() + pitch / 2 - gapH / 2.f;
        const int m = std::max(1, gapH);
        const int l0 = cvRound(gridLeft + gapH / 2.f), l1 = cvRound(rowLeft) - m;
        const int r0 = cvRound(rowRight) + m, r1 = cvRound(W - gridRight - gapH / 2.f);
        if (l1 - l0 > pitch * 0.5f) shiftKey = clipped({l0, y, l1 - l0, h}, rgb.size());
        if (r1 - r0 > pitch * 0.5f) deleteKey = clipped({r0, y, r1 - r0, h}, rgb.size());
    }

    const auto faceOf = [&](const cv::Rect& r) { return modeColour(rgb(clipped(inset(r, 0.08f, 0.1f), rgb.size()))); };
    const auto inkOf = [&](const cv::Rect& r, const Colour& f, float* strength = nullptr) {
        return inkColour(rgb(clipped(inset(r, 0.12f, 0.12f), rgb.size())), f, strength);
    };

    // Modifier keys: the colour most of them agree on.
    std::vector<cv::Rect> modifiers;
    if (shiftKey.area() > 0) modifiers.push_back(shiftKey);
    if (deleteKey.area() > 0) modifiers.push_back(deleteKey);
    if (modeKey.area() > 0) modifiers.push_back(modeKey);
    Colour modifierFace = faceColour, modifierInk;
    bool haveModifier = false;
    if (!modifiers.empty()) {
        std::vector<Colour> faces, inks;
        for (const auto& k : modifiers) {
            const Colour f = faceOf(k);
            faces.push_back(f);
            inks.push_back(inkOf(k, f));
        }
        modifierFace = medianColour(faces);
        modifierInk = medianColour(inks);
        haveModifier = true;
    }
    Colour enterFace = modifierFace, enterInk = modifierInk;
    if (enterKey.area() > 0) {
        enterFace = faceOf(enterKey);
        enterInk = inkOf(enterKey, enterFace);
    }
    Colour spaceFace = faceColour;
    if (spaceKey.area() > 0) {
        // Not its middle, where a language name sits.
        spaceFace = modeColour(rgb(clipped({spaceKey.x + spaceKey.width / 12, spaceKey.y + spaceKey.height / 6,
                                            spaceKey.width / 6, spaceKey.height * 2 / 3}, rgb.size())));
    }

    // Label colour and size, from the letter rows.
    std::vector<Colour> inks, perKeyFace;
    for (const auto& k : keys) {
        const Colour f = faceOf(k.rect);
        perKeyFace.push_back(f);
        inks.push_back(inkOf(k.rect, f));
    }
    const Colour keyInk = medianColour(inks);

    // Corner hints: small blobs high on the right of a letter key.
    std::vector<float> hintHeights;
    std::vector<Colour> hintInks;
    std::vector<float> labelHeights;
    for (const auto& k : keys) {
        const cv::Rect corner(k.rect.x + k.rect.width / 2, k.rect.y, k.rect.width / 2, k.rect.height / 3);
        Blob biggest{0, 0, 0, 0}, hint{0, 0, 0, 0};
        for (const auto& b : labels) {
            if (!k.rect.contains(cv::Point(cvRound(b.cx), cvRound(b.cy)))) continue;
            if (b.h > biggest.h) biggest = b;
        }
        for (const auto& b : labels) {
            if (corner.contains(cv::Point(cvRound(b.cx), cvRound(b.cy))) && b.h < biggest.h * 0.72f && b.h > hint.h) hint = b;
        }
        // A blob nearly the key's height is its outline or a frame, not a label.
        if (biggest.h > 0 && biggest.h < k.rect.height * 0.72f) labelHeights.push_back(biggest.h);
        if (hint.h > 0) {
            hintHeights.push_back(hint.h);
            const cv::Rect box = clipped({cvRound(hint.cx - hint.w / 2), cvRound(hint.cy - hint.h / 2), cvRound(hint.w) + 1, cvRound(hint.h) + 1}, rgb.size());
            float strength = 0;
            const Colour c = inkColour(rgb(box), faceOf(k.rect), &strength);
            hintInks.push_back(c);
        }
    }
    const bool hasHints = hintHeights.size() * 3 >= keys.size();
    const Colour hintInk = hasHints ? medianColour(hintInks) : keyInk;
    const float labelPx = medianOf(labelHeights);

    // Stroke weight against label height: how thick the thickest part is.
    float strokeRatio = 0;
    {
        std::vector<float> ratios;
        for (const auto& k : keys) {
            const cv::Mat crop = rgb(clipped(inset(k.rect, 0.1f, 0.1f), rgb.size()));
            cv::Mat grey, bin, dist;
            cv::cvtColor(crop, grey, cv::COLOR_RGB2GRAY);
            const double faceGrey = luminance(perKeyFace[&k - keys.data()]) * 255;
            cv::Mat delta;
            cv::absdiff(grey, cv::Scalar(faceGrey), delta);
            cv::threshold(delta, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
            cv::distanceTransform(bin, dist, cv::DIST_L2, 3);
            double most = 0;
            cv::minMaxLoc(dist, nullptr, &most);
            if (most > 0) ratios.push_back(static_cast<float>(most * 2));
        }
        if (labelPx > 0 && !ratios.empty()) strokeRatio = medianOf(ratios) / labelPx;
    }

    // ---- The board, and the bars above and below the keys ----

    // Where the keyboard starts: the strongest edge across the whole width above
    // the keys, or just the top of whatever is drawn there if nothing stands out.
    const int firstTop = faceTop[0];
    int barTop = firstTop;
    {
        const int searchFrom = std::max(0, cvRound(firstTop - rowPitch * 3.2f));
        float bestEdge = 0;
        int bestY = -1;
        const int span = std::max(1, W / 240);
        for (int y = firstTop - gapV - span - 1; y > searchFrom + span; --y) {
            cv::Mat d;
            cv::absdiff(rgb.row(y - span), rgb.row(y + span), d);
            cv::Mat grey;
            cv::cvtColor(d, grey, cv::COLOR_RGB2GRAY);
            const float changed = static_cast<float>(cv::countNonZero(grey > 6)) / W;
            if (changed > 0.75f && changed > bestEdge) { bestEdge = changed; bestY = y; }
        }
        if (bestY >= 0) {
            barTop = bestY;
        } else if (firstTop - gapV - rowPitch * 1.8f <= 0) {
            barTop = 0;
        } else {
            // Nothing to see: as tall as what is drawn above the keys.
            int top = firstTop;
            for (const auto& b : labels) {
                if (b.cy < firstTop && b.cy > firstTop - rowPitch * 1.6f) top = std::min(top, cvRound(b.cy - b.h / 2));
            }
            barTop = std::max(0, top - cvRound((firstTop - top) * 0.25f));
        }
    }
    const int barBottom = std::max(barTop, firstTop - gapV);
    // A crop of a keyboard is wide too. Only a screen shows something above it,
    // and a landscape one is about twice the width the theme will be used at.
    const bool landscape = W > H * 1.05f && barTop > H * 0.08f;
    const float pxPerDp = (W / widthDp) * (landscape ? 0.5f : 1.f);
    const int barH = barBottom - barTop;
    const int navTop = bottomEnd + gapV / 2;
    const int navH = H - navTop;

    // Where the board can be seen: gaps and margins, with labels and keys out.
    cv::Mat coveredAll(rgb.size(), CV_8U, cv::Scalar(0));
    const int grow = std::max(1, std::max(borderPx, shadowPx) + 1);
    const auto cover = [&](const cv::Rect& r) {
        const cv::Rect g = clipped({r.x - grow, r.y - grow, r.width + 2 * grow, r.height + 2 * grow}, rgb.size());
        if (g.area() > 0) coveredAll(g).setTo(255);
    };
    for (const auto& k : keys) cover(k.rect);
    for (const auto& k : bottomKeys) cover(k);
    if (shiftKey.area() > 0) cover(shiftKey);
    if (deleteKey.area() > 0) cover(deleteKey);

    const cv::Rect keyRegion(0, firstTop, W, std::min(H, bottomEnd) - firstTop);
    Colour barColour = gapColour, navColour = gapColour;
    if (barH > 0) barColour = modeColour(rgb({0, barTop, W, barH}));
    if (navH > 0) navColour = modeColour(rgb({0, navTop, W, navH}));

    // Everything that could be board, gaps and margins first. The bars join when
    // they read as the same surface, which a photo or gradient runs behind.
    std::vector<Sample> samples;
    const float boxTop = static_cast<float>(barTop);
    const float boxH = static_cast<float>(H - barTop), boxW = static_cast<float>(W);
    const int step = std::max(1, static_cast<int>(std::sqrt(static_cast<float>(W) * (H - barTop) / 90000.f)));
    const auto takeSamples = [&](const cv::Rect& region) {
        for (int y = region.y; y < region.y + region.height; y += step) {
            for (int x = 0; x < W; x += step) {
                if (coveredAll.at<uint8_t>(y, x)) continue;
                const auto v = rgb.at<cv::Vec3b>(y, x);
                samples.push_back({static_cast<float>(x), y - boxTop, Colour(v[0], v[1], v[2])});
            }
        }
    };
    takeSamples(clipped(keyRegion, rgb.size()));
    const bool barIsBoard = barH > 0 && dE(barColour, gapColour) < 6.f;
    const bool navIsBoard = navH > 0 && dE(navColour, gapColour) < 6.f;
    BoardFit board;
    if (samples.size() > 40) {
        board = fitBoard(samples, boxW, boxH);
    } else {
        // Keys edge to edge: no board shows between them, so the bars speak for it.
        board.solid = barH > 0 ? barColour : (navH > 0 ? navColour : gapColour);
    }
    // A gradient or photo keeps going behind the bars when they have no colour
    // of their own, which the fit to the gaps alone cannot tell. If their pixels
    // fall on the fitted board, the board model holds there too.
    const auto barAgrees = [&](const Colour& seen, int y0, int h) {
        if (board.kind == BoardKind::Solid) return dE(seen, board.solid) < 6.f;
        if (board.kind == BoardKind::Image) return false;
        return dE(seen, boardAt(board, boxW / 2, y0 + h / 2.f - boxTop, boxW, boxH)) < 12.f;
    };
    const bool barOwnColour = barH > 0 && !barIsBoard && !barAgrees(barColour, barTop, barH);
    const bool navOwnColour = navH > 0 && !navIsBoard && !barAgrees(navColour, navTop, navH);

    // Toolbar: filled shapes (tool backgrounds) and what is drawn on them.
    Colour toolbarInk = keyInk, toolFill{0, 0, 0};
    bool hasToolFill = false, hasBarInk = false;
    int toolRadiusPx = 0, toolWidthPx = 0;
    if (barH > rowPitch * 0.25f) {
        const cv::Mat band = rgb({0, barTop, W, barH});
        cv::Mat bandLab;
        band.convertTo(bandLab, CV_32F, 1 / 255.0);
        cv::cvtColor(bandLab, bandLab, cv::COLOR_RGB2Lab);
        const cv::Vec3f bg = toLab(barColour);
        cv::Mat bin(band.size(), CV_8U);
        for (int y = 0; y < band.rows; ++y) {
            for (int x = 0; x < band.cols; ++x) {
                bin.at<uint8_t>(y, x) = cv::norm(bandLab.at<cv::Vec3f>(y, x) - bg) > 5.f ? 255 : 0;
            }
        }
        cv::Mat labelled, stats, centroids;
        const int n = cv::connectedComponentsWithStats(bin, labelled, stats, centroids, 8, CV_32S);
        std::vector<cv::Rect> chips;
        for (int i = 1; i < n; ++i) {
            const cv::Rect r(stats.at<int>(i, cv::CC_STAT_LEFT), stats.at<int>(i, cv::CC_STAT_TOP),
                             stats.at<int>(i, cv::CC_STAT_WIDTH), stats.at<int>(i, cv::CC_STAT_HEIGHT));
            const int area = stats.at<int>(i, cv::CC_STAT_AREA);
            if (r.height > barH * 0.5f && r.height < barH * 0.98f && r.width >= r.height * 0.7f && r.width < W / 3 &&
                area > r.area() * 0.5f) {
                chips.push_back(r);
            }
        }
        if (chips.size() >= 3) {
            std::vector<Colour> fills, chipInks;
            std::vector<int> widths, radii;
            for (const auto& c : chips) {
                const Colour f = modeColour(band(c));
                fills.push_back(f);
                chipInks.push_back(inkColour(band(inset(c, 0.12f, 0.12f)), f));
                widths.push_back(c.width);
                cv::Mat m = bin(c).clone();
                cv::Mat pad(m.rows + 2, m.cols + 2, CV_8U, cv::Scalar(0));
                m.copyTo(pad({1, 1, m.cols, m.rows}));
                // Only the outline: the icon on it must not read as board.
                std::vector<std::vector<cv::Point>> contours;
                cv::findContours(pad, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
                cv::Mat filled = cv::Mat::zeros(pad.size(), CV_8U);
                cv::drawContours(filled, contours, -1, cv::Scalar(255), cv::FILLED);
                ShapeFit f2 = fitShape(filled, {1, 1, m.cols, m.rows});
                radii.push_back(f2.shape == Shape::Sharp ? 0
                                : (f2.shape == Shape::Pill || f2.shape == Shape::Circle) ? std::min(c.width, c.height) / 2
                                : cvRound(f2.radius));
            }
            toolFill = medianColour(fills);
            toolbarInk = medianColour(chipInks);
            toolWidthPx = medianOf(widths);
            toolRadiusPx = medianOf(radii);
            hasToolFill = true;
            hasBarInk = true;
        } else if (cv::countNonZero(bin) > 0) {
            // Bare icons or a row of words. A row of colourful emoji has no one
            // ink, and its saturated median would make a poor text colour.
            const Colour c = inkColour(band, barColour, nullptr, bin);
            if (saturation(c) < 0.65f || saturation(keyInk) > 0.4f) {
                toolbarInk = c;
                hasBarInk = true;
            }
        }
    }

    // Translucent keys: where the board changes under them and their faces
    // change with it, a key's colour is a blend, and the slope of one against
    // the other is how much shows through.
    float keyAlpha = 1.f;
    Colour keyOpaque = faceColour;
    cv::Mat boardPicture;
    if (board.kind == BoardKind::Image || board.kind == BoardKind::Linear || board.kind == BoardKind::Radial ||
        board.kind == BoardKind::Sweep) {
        cv::Mat known;
        cv::bitwise_not(coveredAll, known);
        if (barOwnColour && barH > 0) known({0, barTop, W, barH}).setTo(0);
        if (barTop > 0) known({0, 0, W, barTop}).setTo(0);
        // Bars carry icons: small enough to fill over.
        if (!barOwnColour && barH > 0) {
            cv::Mat labelMask(rgb.size(), CV_8U, cv::Scalar(0));
            for (const auto& b : labels) {
                const cv::Rect r = clipped({cvRound(b.cx - b.w / 2) - 2, cvRound(b.cy - b.h / 2) - 2, cvRound(b.w) + 4, cvRound(b.h) + 4}, rgb.size());
                if (r.y + r.height <= firstTop) labelMask(r).setTo(255);
            }
            known.setTo(0, labelMask);
        }
        cv::Mat filled = pushPull(rgb, known);
        std::vector<float> xs[3], ys[3];
        for (size_t i = 0; i < keys.size(); ++i) {
            const auto center = (keys[i].rect.tl() + keys[i].rect.br()) / 2;
            const auto b = filled.at<cv::Vec3b>(center);
            for (int c = 0; c < 3; ++c) {
                xs[c].push_back(b[c]);
                ys[c].push_back(perKeyFace[i][c]);
            }
        }
        float sxy = 0, sxx = 0, syy = 0;
        Colour mx3, my3;
        for (int c = 0; c < 3; ++c) {
            mx3[c] = std::accumulate(xs[c].begin(), xs[c].end(), 0.f) / xs[c].size();
            my3[c] = std::accumulate(ys[c].begin(), ys[c].end(), 0.f) / ys[c].size();
            for (size_t i = 0; i < xs[c].size(); ++i) {
                sxy += (xs[c][i] - mx3[c]) * (ys[c][i] - my3[c]);
                sxx += (xs[c][i] - mx3[c]) * (xs[c][i] - mx3[c]);
                syy += (ys[c][i] - my3[c]) * (ys[c][i] - my3[c]);
            }
        }
        const float n = static_cast<float>(xs[0].size()) * 3;
        const float r2 = sxx > 0 && syy > 0 ? sxy * sxy / (sxx * syy) : 0;
        if (sxx / n > 16.f && r2 > 0.4f) {
            const float a = clampf(sxy / sxx, 0, 1);
            if (a > 0.06f && a < 0.97f) {
                keyAlpha = 1 - a;
                for (int c = 0; c < 3; ++c) keyOpaque[c] = clampf((my3[c] - a * mx3[c]) / (1 - a), 0, 255);
            } else if (a >= 0.97f) {
                keyAlpha = 0;
            }
        }
        // A picture under translucent keys can be read back out of them.
        if (board.kind == BoardKind::Image) {
            boardPicture = filled;
            if (keyAlpha > 0.02f && keyAlpha < 0.8f) {
                const float through = 1 - keyAlpha;
                for (const auto& k : keys) {
                    for (int y = k.rect.y; y < k.rect.y + k.rect.height; ++y) {
                        for (int x = k.rect.x; x < k.rect.x + k.rect.width; ++x) {
                            const auto seen = rgb.at<cv::Vec3b>(y, x);
                            const Colour s(seen[0], seen[1], seen[2]);
                            // Not a label's pixels.
                            if (cv::norm(s - faceColour) > 70.f) continue;
                            auto& out = boardPicture.at<cv::Vec3b>(y, x);
                            for (int c = 0; c < 3; ++c) {
                                out[c] = cv::saturate_cast<uint8_t>((s[c] - keyAlpha * keyOpaque[c]) / through);
                            }
                        }
                    }
                }
            }
        }
    }
    const Colour keyFaceOut = keyAlpha < 0.99f ? keyOpaque : faceColour;

    // A face with its own light: a sheen is two colours, a picture is more.
    const cv::Rect faceInner = inset(faceBox, 0.1f, 0.1f);
    const Colour faceTopC = meanOf(tile, {faceInner.x, faceInner.y, faceInner.width, faceInner.height / 5});
    const Colour faceBottomC = meanOf(tile, {faceInner.x, faceInner.y + faceInner.height * 4 / 5, faceInner.width, faceInner.height / 5});
    const Colour faceLeftC = meanOf(tile, {faceInner.x, faceInner.y, faceInner.width / 5, faceInner.height});
    const Colour faceRightC = meanOf(tile, {faceInner.x + faceInner.width * 4 / 5, faceInner.y, faceInner.width / 5, faceInner.height});
    const float sheenV = dE(faceTopC, faceBottomC), sheenH = dE(faceLeftC, faceRightC);
    const bool faceSheen = keyAlpha > 0.99f && std::max(sheenV, sheenH) > 5.f;
    bool faceTexture = false;
    if (keyAlpha > 0.99f) {
        // What a plane cannot account for across the face is a texture. Only
        // around its rim: the median of the labels leaves a ghost in the middle,
        // and a ghost drawn on every key would be the worst thing to copy.
        cv::Mat f;
        tile(faceInner).convertTo(f, CV_32FC3);
        double total = 0;
        int counted = 0;
        for (int y = 0; y < f.rows; ++y) {
            const float ty = static_cast<float>(y) / std::max(1, f.rows - 1);
            for (int x = 0; x < f.cols; ++x) {
                const float tx = static_cast<float>(x) / std::max(1, f.cols - 1);
                if (std::abs(tx - 0.5f) < 0.3f && std::abs(ty - 0.5f) < 0.3f) continue;
                const Colour v = sheenV >= sheenH ? faceTopC * (1 - ty) + faceBottomC * ty : faceLeftC * (1 - tx) + faceRightC * tx;
                total += cv::norm(f.at<cv::Vec3f>(y, x) - v);
                ++counted;
            }
        }
        faceTexture = counted > 0 && total / counted > 14.0 && board.kind != BoardKind::Image;
    }

    // Keys on their own: a coloured board of keys has a face per key.
    std::map<std::string, Colour> overrides;
    std::map<std::string, Colour> overrideInk;
    {
        // Only on a board where the letters follow QWERTY's counts.
        std::vector<int> letterRows;
        for (int r = 0; r < rowCount; ++r) letterRows.push_back(r);
        const int firstLetters = rowCount >= 4 ? rowCount - 3 : 0;
        if (keyAlpha > 0.99f && rowCount - firstLetters == 3) {
            for (size_t i = 0; i < keys.size(); ++i) {
                const auto& k = keys[i];
                const int letterRow = k.row - firstLetters;
                std::string id;
                if (letterRow >= 0) {
                    const std::string line = kQwerty[letterRow];
                    if (static_cast<int>(grid.rows[k.row].xs.size()) == static_cast<int>(line.size())) id = line.substr(k.index, 1);
                } else if (grid.rows[k.row].xs.size() == 10) {
                    id = std::to_string((k.index + 1) % 10);
                }
                if (!id.empty() && dE(perKeyFace[i], faceColour) > 8.f) {
                    overrides[id] = perKeyFace[i];
                    overrideInk[id] = inks[i];
                }
            }
        }
        if (spaceKey.area() > 0 && dE(spaceFace, faceColour) > 6.f && dE(spaceFace, modifierFace) > 6.f && keyAlpha > 0.99f) {
            overrides["SPACE"] = spaceFace;
        }
    }

    // ---- The theme ----

    const Colour darkTest = board.kind == BoardKind::Image ? modeColour(rgb(keyRegion)) : (barOwnColour ? barColour : board.solid);
    const float backgroundLum = board.kind == BoardKind::Solid || board.kind == BoardKind::Image
        ? luminance(darkTest)
        : luminance(medianColour(board.stops));
    const bool dark = backgroundLum < 0.5f;

    // Accent: the most colourful thing the picture calls out, the enter key's
    // first, then a tool's or the label's.
    Colour accent = enterFace;
    if (saturation(accent) < 0.2f && dE(enterFace, modifierFace) < 8.f) {
        if (hasToolFill && saturation(toolFill) > 0.25f) accent = toolFill;
        else if (saturation(keyInk) > 0.25f) accent = keyInk;
    }

    const float gapHdp = gapH / pxPerDp, gapVdp = gapV / pxPerDp;

    Json theme;
    theme.str("id", "copied");
    theme.str("name", "");
    theme.boolean("dark", dark);
    // Board. A picture draws over a see-through board colour.
    if (board.kind == BoardKind::Image) {
        theme.raw("boardBackground", argb(darkTest, 0.f));
    } else {
        theme.raw("boardBackground", argb(board.solid));
    }
    if (board.kind == BoardKind::Linear || board.kind == BoardKind::Radial || board.kind == BoardKind::Sweep) {
        const char* type = board.kind == BoardKind::Linear ? "LINEAR" : board.kind == BoardKind::Radial ? "RADIAL" : "SWEEP";
        theme.raw("boardGradient", gradientJson(board.stops, type, board.angle));
    }
    if (barOwnColour) theme.raw("suggestionBarBackground", argb(barColour));
    if (navOwnColour) theme.raw("navigationBarBackground", argb(navColour));

    if (faceless) {
        theme.str("keyShape", "NONE");
    } else {
        theme.str("keyShape", shapeName(shape.shape));
    }
    if (!faceless && (shape.shape == Shape::Rounded || shape.shape == Shape::Cut)) {
        theme.integer("keyCornerRadiusDp", cvRound(shape.radius / pxPerDp));
    }
    theme.raw("keyBackground", argb(keyFaceOut, keyAlpha));
    theme.raw("keyText", argb(keyInk));
    theme.raw("modifierKeyBackground", argb(haveModifier ? modifierFace : keyFaceOut, haveModifier ? (keyAlpha < 0.99f && dE(modifierFace, faceColour) < 3.f ? keyAlpha : 1.f) : keyAlpha));
    if (haveModifier) theme.raw("modifierKeyText", argb(modifierInk));
    theme.raw("enterKeyBackground", argb(enterFace));
    theme.raw("enterKeyText", argb(enterInk));
    if (hasHints) theme.raw("hintText", argb(hintInk));
    if (faceSheen) {
        const bool vertical = sheenV >= sheenH;
        const std::vector<Colour> stops = vertical ? std::vector<Colour>{faceTopC, faceBottomC} : std::vector<Colour>{faceLeftC, faceRightC};
        theme.raw("keyGradient", gradientJson(stops, "LINEAR", vertical ? 90.f : 0.f));
    }
    if (borderPx > 0) {
        theme.raw("keyBorderColor", argb(borderColour));
        theme.num("keyBorderWidthDp", clampf(borderPx / pxPerDp, 0.5f, 6.f));
    }
    if (shadowPx > 0) {
        theme.num("keyElevationDp", clampf(shadowPx / pxPerDp, 0.5f, 12.f));
        theme.raw("keyShadowColor", argb(shadowColour));
    }
    theme.raw("accent", argb(accent));
    theme.raw("toolbarIcon", argb(toolbarInk));
    theme.raw("suggestionText", argb(hasBarInk ? toolbarInk : keyInk));
    if (hasToolFill) {
        theme.raw("toolCircleBackground", argb(toolFill));
        theme.integer("toolCircleRadiusDp", cvRound(toolRadiusPx / pxPerDp));
        theme.integer("toolWidthDp", clampf(cvRound(toolWidthPx / pxPerDp), 28, 64));
    } else if (barH > 0) {
        // Bare icons: 0 is no background at all.
        theme.integer("toolCircleRadiusDp", 0);
    }
    if (!overrides.empty()) {
        Json map;
        for (const auto& [id, face] : overrides) {
            Json one;
            one.raw("background", argb(face));
            const auto ink = overrideInk.find(id);
            if (ink != overrideInk.end() && dE(ink->second, keyInk) > 8.f) one.raw("text", argb(ink->second));
            map.raw(id, one.done());
        }
        theme.raw("keyOverrides", map.done());
    }
    const float xHeightDp = labelPx / pxPerDp;
    // At font scale 1 the keyboard's median label blob is 16.7 dp tall, measured
    // on its own captures.
    if (labelPx > 0) theme.num("fontScale", clampf(xHeightDp / 16.7f, 0.5f, 1.8f));
    theme.boolean("boldKeyLabels", strokeRatio > 0.24f);

    // Sizes this theme can pin, here because a picture's proportions are as
    // much of its look as its colours are. A landscape grid has no portrait
    // height to offer.
    const float gapScale = clampf((gapHdp / 5.f + gapVdp / 8.f) / 2.f, 0.f, 4.f);
    const float keyHeightDp = th / pxPerDp;
    Json sizing;
    sizing.boolean("numberRow", rowCount >= 4);
    sizing.boolean("landscape", landscape);
    sizing.integer("letterRows", rowCount >= 4 ? rowCount - 1 : rowCount);
    sizing.integer("keysAcross", across);
    sizing.num("keyHeightDp", keyHeightDp);
    sizing.num("numberRowHeightDp", rowCount >= 4 ? (faceBottom[0] - faceTop[0]) / pxPerDp : 0.f);
    sizing.num("bottomRowHeightDp", bottomH / pxPerDp);
    sizing.num("keyGapScale", gapScale);
    sizing.num("keyGapHorizontalDp", gapHdp);
    sizing.num("keyGapVerticalDp", gapVdp);
    sizing.num("sidePadLeftScale", clampf(gridLeft / W, 0.f, 0.3f));
    sizing.num("sidePadRightScale", clampf(gridRight / W, 0.f, 0.3f));
    sizing.num("toolbarHeightDp", barH / pxPerDp);
    sizing.num("bottomGapDp", navH / pxPerDp);
    sizing.num("keyboardHeightDp", (H - barTop) / pxPerDp);
    sizing.boolean("fullScreenshot", barTop > H * 0.12f && H > W);
    sizing.num("labelHeightDp", xHeightDp);
    sizing.num("strokeRatio", strokeRatio);
    if (hasHints) sizing.num("hintHeightDp", medianOf(hintHeights) / pxPerDp);

    Json report;
    report.str("grid", grid.method);
    report.str("board", boardName(board.kind));
    report.num("boardResidual", board.residual);
    report.num("keyAlpha", keyAlpha);
    report.num("shapeError", shape.error);
    report.integer("rows", rowCount);
    report.integer("keys", static_cast<int>(keys.size()));
    report.integer("barTop", barTop);
    report.integer("workWidth", W);

    // Pictures, in RGBA.
    if (board.kind == BoardKind::Image && !boardPicture.empty()) {
        cv::Mat cut = boardPicture({0, barTop, W, H - barTop}), out;
        cv::cvtColor(cut, out, cv::COLOR_RGB2RGBA);
        result.images.emplace_back("backgroundImage", out);
    }
    if (faceTexture && !faceless) {
        cv::Mat out;
        cv::cvtColor(tile(faceBox), out, cv::COLOR_RGB2RGBA);
        result.images.emplace_back("keyTexture", out);
        theme.str("keyTextureScale", "stretch");
    }

    // ---- Redrawing it, to see how much of the picture this accounts for ----

    cv::Mat redraw(rgb.size(), CV_8UC3);
    if (board.kind == BoardKind::Image && !boardPicture.empty()) {
        boardPicture.copyTo(redraw);
    } else {
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const Colour c = boardAt(board, static_cast<float>(x), y - boxTop, boxW, boxH);
                redraw.at<cv::Vec3b>(y, x) = {cv::saturate_cast<uint8_t>(c[0]), cv::saturate_cast<uint8_t>(c[1]), cv::saturate_cast<uint8_t>(c[2])};
            }
        }
    }
    if (barOwnColour) redraw({0, barTop, W, barH}).setTo(cv::Scalar(barColour[0], barColour[1], barColour[2]));
    if (navOwnColour && navH > 0) redraw({0, navTop, W, navH}).setTo(cv::Scalar(navColour[0], navColour[1], navColour[2]));
    const auto paintKey = [&](const cv::Rect& r, const Colour& fill, float alpha) {
        const cv::Rect box(r.x - borderPx, r.y - borderPx, r.width + 2 * borderPx, r.height + 2 * borderPx);
        cv::Mat mask = cv::Mat::zeros(rgb.size(), CV_8U);
        drawShape(mask, faceless ? Shape::Sharp : shape.shape, box, shape.radius);
        if (faceless) return;
        if (shadowPx > 0) {
            cv::Mat shifted = cv::Mat::zeros(rgb.size(), CV_8U);
            drawShape(shifted, shape.shape, {box.x, box.y + shadowPx, box.width, box.height}, shape.radius);
            redraw.setTo(cv::Scalar(shadowColour[0], shadowColour[1], shadowColour[2]), shifted);
        }
        if (borderPx > 0) redraw.setTo(cv::Scalar(borderColour[0], borderColour[1], borderColour[2]), mask);
        cv::Mat inner = cv::Mat::zeros(rgb.size(), CV_8U);
        drawShape(inner, shape.shape, r, std::max(0.f, shape.radius - borderPx));
        if (alpha >= 0.99f) {
            redraw.setTo(cv::Scalar(fill[0], fill[1], fill[2]), inner);
        } else {
            for (int y = std::max(0, r.y); y < std::min(H, r.y + r.height); ++y) {
                for (int x = std::max(0, r.x); x < std::min(W, r.x + r.width); ++x) {
                    if (!inner.at<uint8_t>(y, x)) continue;
                    auto& p = redraw.at<cv::Vec3b>(y, x);
                    for (int c = 0; c < 3; ++c) p[c] = cv::saturate_cast<uint8_t>(fill[c] * alpha + p[c] * (1 - alpha));
                }
            }
        }
    };
    for (size_t i = 0; i < keys.size(); ++i) paintKey(keys[i].rect, keyAlpha < 0.99f ? keyOpaque : perKeyFace[i], keyAlpha);
    for (const auto& k : bottomKeys) {
        const Colour f = k == enterKey ? enterFace : k == modeKey ? modifierFace : k == spaceKey ? spaceFace : faceOf(k);
        paintKey(k, f, 1.f);
    }
    if (shiftKey.area() > 0) paintKey(shiftKey, faceOf(shiftKey), 1.f);
    if (deleteKey.area() > 0) paintKey(deleteKey, faceOf(deleteKey), 1.f);

    float confidence = 0;
    {
        // Only the keyboard, and only surfaces, since labels are not redrawn.
        const cv::Rect area(0, barBottom, W, std::min(H, bottomEnd + gapV) - barBottom);
        cv::Mat a, b;
        rgb(area).convertTo(a, CV_32F, 1 / 255.0);
        redraw(area).convertTo(b, CV_32F, 1 / 255.0);
        cv::cvtColor(a, a, cv::COLOR_RGB2Lab);
        cv::cvtColor(b, b, cv::COLOR_RGB2Lab);
        std::vector<float> errors;
        for (int y = 0; y < a.rows; y += 2) {
            for (int x = 0; x < a.cols; x += 2) {
                errors.push_back(static_cast<float>(cv::norm(a.at<cv::Vec3f>(y, x) - b.at<cv::Vec3f>(y, x))));
            }
        }
        // Labels are a few percent of the area, so the 85th percentile skips them.
        std::nth_element(errors.begin(), errors.begin() + errors.size() * 85 / 100, errors.end());
        const float typical = errors[errors.size() * 85 / 100];
        confidence = clampf(1.f - typical / 25.f, 0, 1);
    }
    report.num("confidence", confidence);
    theme.raw("_copy", [&] {
        Json c;
        c.raw("sizing", sizing.done());
        c.raw("report", report.done());
        return c.done();
    }());

    result.found = true;
    result.json = theme.done();
    result.confidence = confidence;

    if (debug) {
        result.redrawn = redraw;
        cv::Mat overlay = rgb.clone();
        const auto mark = [&](const cv::Rect& r, cv::Scalar c) { cv::rectangle(overlay, r, c, std::max(1, W / 540)); };
        for (const auto& k : keys) mark(k.rect, {0, 255, 0});
        for (const auto& k : bottomKeys) mark(k, {255, 200, 0});
        if (shiftKey.area() > 0) mark(shiftKey, {255, 0, 255});
        if (deleteKey.area() > 0) mark(deleteKey, {255, 0, 255});
        if (barH > 0) mark({1, barTop, W - 2, barH}, {0, 180, 255});
        if (navH > 0) mark({1, navTop, W - 2, navH}, {255, 80, 80});
        result.overlay = overlay;
    }
    return result;
}

}  // namespace

ThemeCopy copyTheme(const cv::Mat& rgba, float widthDp, bool debug) {
    ThemeCopy first = copyWithGrid(rgba, widthDp, debug, false);
    if (first.found && first.confidence >= 0.7f) return first;
    // The redraw says this reading of the grid does not hold: ask the other one.
    ThemeCopy second = copyWithGrid(rgba, widthDp, debug, true);
    return second.found && (!first.found || second.confidence > first.confidence) ? second : first;
}

}  // namespace wmdocscan
