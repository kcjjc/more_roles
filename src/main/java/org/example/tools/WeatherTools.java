package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * @author ckj
 */
@Component
public class WeatherTools {
    @Tool(description = """
            查询指定城市的天气
            """)
    public String getWeather(@ToolParam(description = "查询天气的城市名称，例如福州等") String city) {
        return "查询 " + city + " 的天气为晴天";
    }
}
