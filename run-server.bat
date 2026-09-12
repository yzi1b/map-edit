@echo off
rem MapEdit 一键测试服务器（Paper 26.2）
rem 首次运行会自动下载 Paper 并生成 run/ 目录；自动部署本插件最新构建
rem 关闭：在窗口内按 Ctrl+C 可让服务器正常保存退出
cd /d %~dp0
call gradlew.bat runServer
if errorlevel 1 pause
