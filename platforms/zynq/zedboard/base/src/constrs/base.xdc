set_property PACKAGE_PIN Y9 [get_ports clk]
set_property IOSTANDARD LVCMOS33 [get_ports clk]
create_clock -name gclk_0 -period "10" -waveform {0.0 5.0} [get_ports clk]
