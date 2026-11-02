cmake_minimum_required(VERSION 3.22)
project(darkyqrtr C)

add_library(darkyqrtr SHARED qrtr.c)
target_compile_options(darkyqrtr PRIVATE -Wall -Wextra -O2)
target_link_libraries(darkyqrtr log)
