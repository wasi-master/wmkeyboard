# The desktop check's command-line tool: docscan.cpp over a host build of the
# same OpenCV (check.sh builds it with ../opencv.cmake).
cmake_minimum_required(VERSION 3.18)
project(docscan_cli CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(OpenCV REQUIRED COMPONENTS core imgproc geometry)

add_executable(docscan_cli docscan_cli.cpp ../docscan.cpp)
target_include_directories(docscan_cli PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(docscan_cli PRIVATE ${OpenCV_LIBS})

# The theme copier's, driven by tools/themecopy/.
add_executable(themecopy_cli themecopy_cli.cpp ../themecopy.cpp)
target_include_directories(themecopy_cli PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(themecopy_cli PRIVATE ${OpenCV_LIBS})
