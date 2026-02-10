package org.secretjuju.kono.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class UpbitMarketResponse {
	@JsonProperty("market")
	private String market;
	@JsonProperty("korean_name")
	private String koreanName;
	@JsonProperty("english_name")
	private String englishName;
}
