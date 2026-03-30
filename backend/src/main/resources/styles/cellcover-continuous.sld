<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor version="1.0.0"
  xmlns="http://www.opengis.net/sld"
  xmlns:ogc="http://www.opengis.net/ogc"
  xmlns:xlink="http://www.w3.org/1999/xlink"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.opengis.net/sld
    http://schemas.opengis.net/sld/1.0.0/StyledLayerDescriptor.xsd">
  <NamedLayer>
    <Name>cellcover-continuous</Name>
    <UserStyle>
      <Title>Cell Cover Signal Strength (Max)</Title>
      <Abstract>Signal strength color ramp in dBm. NoData (-3.4E38) is transparent.
        Ramp: below -170 dBm = dark blue; -75 dBm and above = yellow.
        Overview downsampling uses AVERAGE (COG OVERVIEW_RESAMPLING=AVERAGE).
      </Abstract>
      <FeatureTypeStyle>
        <Rule>
          <RasterSymbolizer>
            <ChannelSelection>
              <GrayChannel>
                <SourceChannelName>1</SourceChannelName>
              </GrayChannel>
            </ChannelSelection>
            <ColorMap type="ramp">
              <!-- GDAL NoData sentinel (-3.4028235E38) → fully transparent -->
              <ColorMapEntry color="#000000" quantity="-3.4028235E38" opacity="0.0"/>
              <!-- All values below -170 dBm (very weak or out-of-range) → dark blue, opaque.
                   Linear interpolation from -3.4E38 (opacity=0) to -170 (opacity=0.70) means
                   any real dBm value below -170 (e.g. -250) will appear as ~#00204D at ~70% opacity. -->
              <ColorMapEntry color="#00204D" quantity="-170" opacity="0.70" label="&lt; -170 dBm"/>
              <!-- Signal strength ramp matching frontend STOPS / COLORS arrays -->
              <ColorMapEntry color="#00336F" quantity="-150" opacity="0.70" label="-150 dBm"/>
              <ColorMapEntry color="#1F4E79" quantity="-130" opacity="0.70" label="-130 dBm"/>
              <ColorMapEntry color="#2C788E" quantity="-110" opacity="0.70" label="-110 dBm"/>
              <ColorMapEntry color="#5FA060" quantity="-95"  opacity="0.70" label="-95 dBm"/>
              <ColorMapEntry color="#9DBA46" quantity="-85"  opacity="0.70" label="-85 dBm"/>
              <ColorMapEntry color="#D2CE3E" quantity="-75"  opacity="0.70" label="-75 dBm"/>
              <ColorMapEntry color="#FDE725" quantity="0"    opacity="0.70" label=">= -75 dBm"/>
            </ColorMap>
          </RasterSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
