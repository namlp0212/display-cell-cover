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
      <Abstract>Cividis 8-stop color ramp for continuous (non-SVR) raster data</Abstract>
      <FeatureTypeStyle>
        <Rule>
          <RasterSymbolizer>
            <ChannelSelection>
              <GrayChannel>
                <SourceChannelName>1</SourceChannelName>
              </GrayChannel>
            </ChannelSelection>
            <ColorMap type="ramp">
              <!-- NoData-like values: transparent -->
              <ColorMapEntry color="#00204D" quantity="-1e+37" opacity="0"/>
              <!-- Cividis 8-stop ramp: 0–8000 -->
              <ColorMapEntry color="#00204D" quantity="0"    opacity="0.70" label="0"/>
              <ColorMapEntry color="#00336F" quantity="1140"  opacity="0.70" label="1140"/>
              <ColorMapEntry color="#1F4E79" quantity="2280"  opacity="0.70" label="2280"/>
              <ColorMapEntry color="#2C788E" quantity="3420"  opacity="0.70" label="3420"/>
              <ColorMapEntry color="#5FA060" quantity="4560"  opacity="0.70" label="4560"/>
              <ColorMapEntry color="#9DBA46" quantity="5700"  opacity="0.70" label="5700"/>
              <ColorMapEntry color="#D2CE3E" quantity="6850"  opacity="0.70" label="6850"/>
              <ColorMapEntry color="#FDE725" quantity="8000"  opacity="0.70" label="8000"/>
            </ColorMap>
          </RasterSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
