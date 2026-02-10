package org.secretjuju.kono.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.secretjuju.kono.dto.response.UpbitMarketResponse;
import org.secretjuju.kono.entity.CoinInfo;
import org.secretjuju.kono.repository.CoinInfoRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoinInfoSyncService {

	private final CoinInfoRepository coinInfoRepository;
	private final RestTemplate restTemplate;

	private static final String UPBIT_MARKET_URL = "https://api.upbit.com/v1/market/all";

	@Scheduled(fixedRate = 10000) // 10초마다 실행
	@Transactional
	public void syncCoinInfo() {
		try {
			// 1. 업비트 API 호출
			UpbitMarketResponse[] responses = restTemplate.getForObject(UPBIT_MARKET_URL, UpbitMarketResponse[].class);
			if (responses == null) {
				log.warn("업비트 API 응답이 비어있습니다.");
				return;
			}

			// 2. KRW 마켓만 필터링 및 Map으로 변환 (Key: Ticker, Value: UpbitMarketResponse)
			Map<String, UpbitMarketResponse> apiMarketMap = List.of(responses).stream()
					.filter(market -> market.getMarket().startsWith("KRW-"))
					.collect(Collectors.toMap(market -> market.getMarket().replace("KRW-", ""), // Ticker 추출 (예: BTC)
							Function.identity(), (existing, replacement) -> existing // 중복 시 기존 값 유지
					));

			// 3. DB에 저장된 코인 정보 조회
			List<CoinInfo> dbCoinInfos = coinInfoRepository.findAll();
			Map<String, CoinInfo> dbCoinMap = dbCoinInfos.stream()
					.collect(Collectors.toMap(CoinInfo::getTicker, Function.identity()));

			// 4. 삭제 대상 처리 (DB에는 있지만 API에는 없는 경우)
			List<CoinInfo> toDelete = dbCoinInfos.stream().filter(coin -> !apiMarketMap.containsKey(coin.getTicker()))
					.collect(Collectors.toList());

			if (!toDelete.isEmpty()) {
				coinInfoRepository.deleteAll(toDelete);
				log.info("{}개의 코인이 삭제되었습니다: {}", toDelete.size(),
						toDelete.stream().map(CoinInfo::getTicker).collect(Collectors.toList()));
			}

			// 5. 추가 및 업데이트 대상 처리
			for (Map.Entry<String, UpbitMarketResponse> entry : apiMarketMap.entrySet()) {
				String ticker = entry.getKey();
				UpbitMarketResponse marketInfo = entry.getValue();
				String koreanName = marketInfo.getKoreanName();

				if (dbCoinMap.containsKey(ticker)) {
					// 이미 존재하는 경우, 이름이 변경되었는지 확인
					CoinInfo existingCoin = dbCoinMap.get(ticker);
					if (!existingCoin.getKrCoinName().equals(koreanName)) {
						existingCoin.setKrCoinName(koreanName);
						// Dirty Checking에 의해 자동 업데이트됨
						log.info("코인 이름 업데이트: {} -> {}", existingCoin.getTicker(), koreanName);
					}
				} else {
					// 새로운 코인 추가
					CoinInfo newCoin = new CoinInfo();
					newCoin.setTicker(ticker);
					newCoin.setKrCoinName(koreanName);
					coinInfoRepository.save(newCoin);
					log.info("새로운 코인 추가: {} ({})", ticker, koreanName);
				}
			}

		} catch (Exception e) {
			log.error("코인 정보 동기화 중 오류 발생", e);
		}
	}
}
