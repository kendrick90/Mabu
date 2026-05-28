cmake_minimum_required(VERSION 3.22)
project(mabuserial C)

add_library(mabuserial SHARED serial.c)
target_link_libraries(mabuserial log)
