cmake_minimum_required(VERSION 3.22.1)
project("livecore" CXX)

add_library(livecore SHARED livecore.cpp)

target_include_directories(livecore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(livecore ${log-lib})
