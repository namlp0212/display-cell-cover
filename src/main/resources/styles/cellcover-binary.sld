<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor version="1.0.0"
  xmlns="http://www.opengis.net/sld"
  xmlns:ogc="http://www.opengis.net/ogc"
  xmlns:xlink="http://www.w3.org/1999/xlink"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.opengis.net/sld
    http://schemas.opengis.net/sld/1.0.0/StyledLayerDescriptor.xsd">
  <NamedLayer>
    <Name>cellcover-binary</Name>
    <UserStyle>
      <Title>Cell Cover Binary</Title>
      <Abstract>Cividis teal for valid data, transparent for NoData-like values</Abstract>
      <FeatureTypeStyle>
        <Rule>
          <RasterSymbolizer>
            <ChannelSelection>
              <GrayChannel>
                <SourceChannelName>1</SourceChannelName>
              </GrayChannel>
            </ChannelSelection>
            <ColorMap type="intervals">
              <!-- Values <= -200: NoData (-3.4e38) or below valid dBm range → transparent -->
              <ColorMapEntry color="#2C788E" quantity="-200" opacity="0"/>
              <!-- Values > -200: actual dBm signal (-140 to -44 dBm) → semi-transparent -->
              <ColorMapEntry color="#2C788E" quantity="1e+38" opacity="0.55"/>
            </ColorMap>
          </RasterSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
