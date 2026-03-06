@echo off
setlocal
cd /d %~dp0
python train_lstm_one_click.py
endlocal
