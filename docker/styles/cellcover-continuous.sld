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
      <Title>Cell Cover Continuous</Title>
      <Abstract>Signal strength color ramp for dBm values (-170 to -75 dBm scale)</Abstract>
      <FeatureTypeStyle>
        <Rule>
          <RasterSymbolizer>
            <ChannelSelection>
              <GrayChannel>
                <SourceChannelName>1</SourceChannelName>
              </GrayChannel>
            </ChannelSelection>
            <ColorMap type="ramp">
              <!-- COG nodata sentinel: transparent -->
              <ColorMapEntry color="#00204D" quantity="-1e+37" opacity="0" label="nodata"/>
              <!-- dBm color ramp: -190 to -75 dBm — gradient below -130 reveals weak-signal variation -->
              <ColorMapEntry color="#020308" quantity="-190" opacity="0.70" label="&lt;-190"/>
              <ColorMapEntry color="#010B17" quantity="-170" opacity="0.70" label="-170"/>
              <ColorMapEntry color="#011020" quantity="-160" opacity="0.70" label="-160"/>
              <ColorMapEntry color="#011629" quantity="-150" opacity="0.70" label="-150"/>
              <ColorMapEntry color="#011C36" quantity="-140" opacity="0.70" label="-140"/>
              <ColorMapEntry color="#00204D" quantity="-130" opacity="0.70" label="-130"/>
              <ColorMapEntry color="#00336F" quantity="-120" opacity="0.70" label="-120"/>
              <ColorMapEntry color="#1F4E79" quantity="-110" opacity="0.70" label="-110"/>
              <ColorMapEntry color="#2C788E" quantity="-100" opacity="0.70" label="-100"/>
              <ColorMapEntry color="#5FA060" quantity="-90"  opacity="0.70" label="-90"/>
              <ColorMapEntry color="#9DBA46" quantity="-85"  opacity="0.70" label="-85"/>
              <ColorMapEntry color="#D2CE3E" quantity="-80"  opacity="0.70" label="-80"/>
              <ColorMapEntry color="#FDE725" quantity="-75"  opacity="0.70" label="-75"/>
            </ColorMap>
          </RasterSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
