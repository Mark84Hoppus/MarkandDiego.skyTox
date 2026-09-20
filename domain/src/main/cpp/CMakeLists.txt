cmake_minimum_required(VERSION 3.22.1)

project(skytox_video)

add_library(skytox_video SHARED skytox_video.cpp)

target_compile_options(skytox_video PRIVATE -O3 -fno-exceptions -fno-rtti)

find_library(log-lib log)
target_link_libraries(skytox_video ${log-lib})
