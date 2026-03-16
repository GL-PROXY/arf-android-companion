cmake_minimum_required(VERSION 3.22.1)
project("psadecrypt")

add_library(tea_bruteforce SHARED tea_bruteforce.c)
target_compile_options(tea_bruteforce PRIVATE -O3 -Wall)
target_link_libraries(tea_bruteforce android log)

add_library(keeloq_bruteforce SHARED keeloq_bruteforce.c)
target_compile_options(keeloq_bruteforce PRIVATE -O3 -Wall)
target_link_libraries(keeloq_bruteforce android log)
