cmake_minimum_required(VERSION 3.16)
project(paodekuai_ai_cpp LANGUAGES CXX)

# 库零第三方依赖: 权重是 STORED(不压缩) 的 TorchScript zip 条目,
# 自己解析中央目录; golden 的 gzip 只有测试用, 缺 zlib 时退化为读已解压文本。
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Threads REQUIRED)

add_library(paodekuai STATIC
  src/cards.cpp
  src/moves.cpp
  src/oracle.cpp
  src/game_state.cpp
  src/perfect_pao_features.cpp
  src/shared_data.cpp
  src/policy.cpp
  src/pt_archive.cpp
)
target_include_directories(paodekuai PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
if(MSVC)
    target_compile_options(paodekuai PRIVATE /utf-8 /W4)
else()
    # -ffp-contract=off 保证浮点结果与 Java/Go 逐位一致 (特征标量与 logits)
    target_compile_options(paodekuai PRIVATE -Wall -Wextra -ffp-contract=off)
endif()
target_link_libraries(paodekuai PUBLIC Threads::Threads)

# server/ 宿主经由 add_subdirectory 引入本目录时会关掉测试,
# 免得生产机上还要拉 googletest。
if(NOT DEFINED PAODEKUAI_BUILD_TESTS)
  set(PAODEKUAI_BUILD_TESTS ON)
endif()

if(PAODEKUAI_BUILD_TESTS)
  find_package(GTest QUIET)
  find_package(ZLIB QUIET)
  if(GTest_FOUND)
      set(GTEST_LIBRARIES GTest::gtest GTest::gtest_main)
  else()
      include(FetchContent)
      FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
      )
      set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
      FetchContent_MakeAvailable(googletest)
      set(GTEST_LIBRARIES gtest gtest_main)
  endif()

  add_executable(paodekuai_parity_test test/parity_test.cpp)
  target_include_directories(paodekuai_parity_test PRIVATE src test)
  target_link_libraries(paodekuai_parity_test PRIVATE paodekuai ${GTEST_LIBRARIES})
  if(ZLIB_FOUND)
      target_compile_definitions(paodekuai_parity_test PRIVATE PAODEKUAI_HAVE_ZLIB=1)
      target_link_libraries(paodekuai_parity_test PRIVATE ZLIB::ZLIB)
  endif()
  # 测试工作目录固定为 cpp/, 这样 ResolveSharedData 能向上找到 ../data
  enable_testing()
  add_test(NAME parity COMMAND paodekuai_parity_test WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR})
endif()
