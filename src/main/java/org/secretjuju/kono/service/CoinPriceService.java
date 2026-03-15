package org.secretjuju.kono.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.secretjuju.kono.repository.CoinInfoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoinPriceService {

	private final ConcurrentHashMap<String, Double> coinPriceMap = new ConcurrentHashMap<>();
	private final RestTemplate restTemplate;
	private final CoinInfoRepository coinInfoRepository;
	private final ObjectMapper objectMapper;

	// API 요청 당 최대 티커 수 (업비트 API 제한)
	private static final int MAX_TICKERS_PER_REQUEST = 100;

	// 업비트 API URL
	@Value("${upbit.api.url:https://api.upbit.com/v1}")
	private String upbitApiUrl;

	// 코인 티커 정보 DTO
	public static class TickerDto {
		private String market;

		@JsonProperty("trade_price")
		private Double tradePrice;

		// Getters and Setters
		public String getMarket() {
			return market;
		}

		public void setMarket(String market) {
			this.market = market;
		}

		public Double getTradePrice() {
			return tradePrice;
		}

		public void setTradePrice(Double tradePrice) {
			this.tradePrice = tradePrice;
		}
	}

	public void updateAllCoinPrices() {
		log.info("코인 가격 업데이트 시작");
		try {
			// CoinInfo 테이블에서 모든 티커 조회
			List<String> allTickers = getAllTickers();

			if (allTickers.isEmpty()) {
				log.warn("업데이트할 코인 티커가 없습니다.");
				return;
			}

			log.info("총 {} 개의 코인 가격을 업데이트합니다.", allTickers.size());

			// 티커를 MAX_TICKERS_PER_REQUEST 개씩 나누어 요청
			List<List<String>> tickerBatches = splitIntoChunks(allTickers, MAX_TICKERS_PER_REQUEST);

			for (List<String> batch : tickerBatches) {
				try {
					// 한 번의 요청으로 여러 티커의 가격 정보 조회
					updatePricesForBatch(batch);

					// API 요청 제한을 피하기 위한 짧은 대기 (필요 시)
					if (tickerBatches.size() > 1) {
						Thread.sleep(200);
					}
				} catch (Exception e) {
					log.error("배치 업데이트 중 오류 발생: {}", e.getMessage());
				}
			}

			log.info("코인 가격 업데이트 완료: {} 개 코인", coinPriceMap.size());
		} catch (Exception e) {
			log.error("코인 가격 업데이트 중 오류 발생", e);
		}
	}

	// 티커 배치에 대한 가격 업데이트
	private void updatePricesForBatch(List<String> tickers) {
		// 티커 목록을 쉼표로 구분된 문자열로 변환
		String tickersParam = String.join(",", tickers);

		// 업비트 API 호출
		String url = upbitApiUrl + "/ticker?markets=" + tickersParam;

		try {
			ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				// JSON 응답을 TickerDto 배열로 파싱
				TickerDto[] tickerArray = objectMapper.readValue(response.getBody(), TickerDto[].class);

				// 가격 맵 업데이트
				for (TickerDto ticker : tickerArray) {
					coinPriceMap.put(ticker.getMarket(), ticker.getTradePrice());
				}

				log.debug("배치 업데이트 성공: {} 개 코인", tickerArray.length);
			} else {
				log.warn("배치 업데이트 실패: 응답 코드 {}", response.getStatusCode());
			}
		} catch (JsonProcessingException e) {
			log.error("JSON 파싱 오류: {}", e.getMessage());
		}
	}

	// CoinInfo 테이블에서 모든 티커 조회
	private List<String> getAllTickers() {
		return coinInfoRepository.findAll().stream().filter(coinInfo -> Boolean.TRUE.equals(coinInfo.getActive()))
				.map(coinInfo -> "KRW-" + coinInfo.getTicker()) // "BTC" -> "KRW-BTC" 형식으로 변환
				.collect(Collectors.toList());
	}

	// 리스트를 지정된 크기의 청크로 분할
	private <T> List<List<T>> splitIntoChunks(List<T> list, int chunkSize) {
		List<List<T>> chunks = new ArrayList<>();

		for (int i = 0; i < list.size(); i += chunkSize) {
			int end = Math.min(list.size(), i + chunkSize);
			chunks.add(new ArrayList<>(list.subList(i, end)));
		}

		return chunks;
	}

	// 코인 티커로 현재가 조회 (KRW-BTC 형식)
	public Double getPrice(String ticker) {
		return coinPriceMap.getOrDefault(ticker, 0.0);
	}

	// 코인 심볼로 현재가 조회 (BTC 형식)
	public Double getPriceByTicker(String ticker) {
		return getPrice("KRW-" + ticker);
	}

	// 모든 코인 현재가 조회
	public Map<String, Double> getAllPrices() {
		return new ConcurrentHashMap<>(coinPriceMap); // 맵 복사본 반환
	}

	// KRW- 접두사 없이 심볼만으로 구성된 맵 반환
	public Map<String, Double> getAllSimplePrices() {
		return coinPriceMap.entrySet().stream().filter(entry -> entry.getKey().startsWith("KRW-"))
				.collect(Collectors.toMap(entry -> entry.getKey().substring(4), // "KRW-BTC" -> "BTC"
						Map.Entry::getValue));
	}

	// 특정 코인 목록의 현재가만 조회
	public Map<String, Double> getPricesForCoins(List<String> symbols) {
		return symbols.stream().collect(Collectors.toMap(symbol -> symbol, this::getPriceByTicker));
	}

	// 현재 캐시에 저장된 코인 개수
	public int getCachedCoinCount() {
		return coinPriceMap.size();
	}
}