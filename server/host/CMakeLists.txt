# paodekuai_server -- 跑得快网页端的 fakelua 宿主。
# 引擎源码直接从 ../../cpp 编进来; fakelua 优先找系统安装, 找不到再从源码编。
cmake_minimum_required(VERSION 3.16)
project(paodekuai_server_host LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(PAODEKUAI_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../cpp paodekuai_cpp_build)

set(FAKELUA_PREFIX "/opt/homebrew" CACHE PATH "已安装 fakelua 的前缀")
find_package(fakelua CONFIG QUIET HINTS "${FAKELUA_PREFIX}"
        PATH_SUFFIXES lib lib64 lib/x86_64-linux-gnu NO_DEFAULT_PATH)
if(NOT fakelua_FOUND)
    if(NOT FAKELUA_DIR)
        message(FATAL_ERROR "在 ${FAKELUA_PREFIX} 找不到 fakeluaConfig.cmake。二选一: "
                "-DFAKELUA_PREFIX=<安装前缀> 或 -DFAKELUA_DIR=<fakelua 源码目录>")
    endif()
    add_subdirectory(${FAKELUA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/fakelua)
endif()
# 装好的 fakelua 导出带命名空间的 target, 源码内编出来的只有裸名
if(TARGET fakelua::fakelua)
    set(FAKELUA_LINK fakelua::fakelua)
else()
    set(FAKELUA_LINK fakelua)
endif()

add_executable(paodekuai_server main.cpp)
target_link_libraries(paodekuai_server PRIVATE paodekuai ${FAKELUA_LINK})
if(WIN32)
    target_link_libraries(paodekuai_server PRIVATE ws2_32)
endif()
