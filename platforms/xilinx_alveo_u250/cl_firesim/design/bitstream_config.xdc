# The majority of these constraints are redundant as them come with the u250 given XDC
set_property CONFIG_VOLTAGE 1.8                        [current_design]
set_property CONFIG_MODE {SPIx4}                       [current_design]
set_property BITSTREAM.CONFIG.CONFIGFALLBACK Enable    [current_design]; # Golden image is the fall back image if  new bitstream is corrupted.
set_property BITSTREAM.CONFIG.EXTMASTERCCLK_EN disable [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 63.8          [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4           [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE           [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE YES        [current_design]
set_property BITSTREAM.CONFIG.SPI_32BIT_ADDR Yes       [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN Pullup         [current_design]
