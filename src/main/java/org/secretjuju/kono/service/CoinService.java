package org.secretjuju.kono.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.secretjuju.kono.dto.request.CoinRequestDto;
import org.secretjuju.kono.dto.request.CoinSellBuyRequestDto;
import org.secretjuju.kono.dto.response.CoinInfoResponseDto;
import org.secretjuju.kono.dto.response.CoinResponseDto;
import org.secretjuju.kono.dto.response.TickerResponse;
import org.secretjuju.kono.entity.CashBalance;
import org.secretjuju.kono.entity.CoinHolding;
import org.secretjuju.kono.entity.CoinInfo;
import org.secretjuju.kono.entity.CoinTransaction;
import org.secretjuju.kono.entity.User;
import org.secretjuju.kono.exception.CustomException;
import org.secretjuju.kono.repository.CashBalanceRepository;
import org.secretjuju.kono.repository.CoinHoldingRepository;
import org.secretjuju.kono.repository.CoinRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CoinService {

	private final CoinRepository coinRepository;
	private final UserService userService;
	private final UpbitService upbitService;
	private final CashBalanceRepository cashBalanceRepository;
	private final CoinHoldingRepository coinHoldingRepository;

	public CoinService(CoinRepository coinRepository, UserService userService, UpbitService upbitService,
			CashBalanceRepository cashBalanceRepository, CoinHoldingRepository coinHoldingRepository) {
		this.coinRepository = coinRepository;
		this.userService = userService;
		this.upbitService = upbitService;
		this.cashBalanceRepository = cashBalanceRepository;
		this.coinHoldingRepository = coinHoldingRepository;
	}

	public List<CoinInfoResponseDto> getAllCoinInfo() {
		List<CoinInfo> coinInfos = coinRepository.findAllByActiveTrue();
		return coinInfos.stream().map(this::convertToCoinInfosResponse).collect(Collectors.toList());
	}

	public CoinResponseDto getCoinByName(CoinRequestDto coinRequestDto) {
		Optional<CoinInfo> coinInfo = coinRepository.findByTickerAndActiveTrue(coinRequestDto.getTicker());
		CoinResponseDto coinResponseDto = new CoinResponseDto(coinInfo.orElse(null));
		return coinResponseDto;
	}

	// 현재가 조회 메소드
	public Double getCurrentPrice(String ticker) {
		// 이미 "KRW-"로 시작하는 경우 그대로 사용, 아니면 "KRW-" 접두사 추가
		String market = ticker.startsWith("KRW-") ? ticker : "KRW-" + ticker;
		TickerResponse tickerResponse = upbitService.getTicker(market);
		return tickerResponse.getTrade_price();
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public void createCoinOrder(CoinSellBuyRequestDto coinSellBuyRequestDto) {
		// 현재 로그인한 사용자 정보를 가져옵니다.
		User currentUser = userService.getCurrentUser();

		// 해당 코인 정보를 가져옵니다.
		Optional<CoinInfo> coinInfoOpt = coinRepository.findByTickerAndActiveTrue(coinSellBuyRequestDto.getTicker());
		if (coinInfoOpt.isEmpty()) {
			throw new CustomException(404, "해당 코인을 찾을 수 없습니다.");
		}
		CoinInfo coinInfo = coinInfoOpt.get();

		// 업비트에서 현재가 조회
		Double currentPrice = getCurrentPrice(coinSellBuyRequestDto.getTicker());

		// 현재가 설정
		coinSellBuyRequestDto.setOrderPrice(currentPrice);

		if (coinSellBuyRequestDto.getOrderAmount() != null && coinSellBuyRequestDto.getOrderQuantity() != null) {
			Long expectedAmount = Math.round(coinSellBuyRequestDto.getOrderQuantity() * currentPrice);
			Long actualAmount = coinSellBuyRequestDto.getOrderAmount();

			// 허용 오차 설정 (예: 1원 이하만 허용)
			long tolerance = 1L;
			if (Math.abs(expectedAmount - actualAmount) > tolerance) {
				throw new CustomException(400, "계산된 주문 금액과 요청 금액의 오차가 너무 큽니다.");
			}
		}
		// orderAmount가 null일 때 수량으로 계산
		if (coinSellBuyRequestDto.getOrderAmount() == null) {
			Long calculatedAmount = Math.round(coinSellBuyRequestDto.getOrderQuantity() * currentPrice);
			coinSellBuyRequestDto.setOrderAmount(calculatedAmount + 1);
		}
		System.out.println("coinSellBuyRequestDto.getOrderType(): " + coinSellBuyRequestDto.getOrderType());
		if (coinSellBuyRequestDto.getOrderQuantity() == null) {
			// 다시 수량 계산
			Double orderQuantity = coinSellBuyRequestDto.getOrderAmount() / currentPrice;
			// orderQuantity = Math.floor(orderQuantity * 100000000) / 100000000;
			// if(orderQuantity <coinSellBuyRequestDto.getOrderQuantity()){
			coinSellBuyRequestDto.setOrderQuantity(orderQuantity);
		}

		// 주문 유효성 검사 및 조정
		validateAndAdjustOrder(currentUser, coinSellBuyRequestDto, coinInfo);

		// 거래 내역을 기록합니다.
		CoinTransaction transaction = new CoinTransaction();
		transaction.setUser(currentUser);
		transaction.setCoinInfo(coinInfo);
		transaction.setOrderType(coinSellBuyRequestDto.getOrderType());
		transaction.setOrderQuantity(coinSellBuyRequestDto.getOrderQuantity());
		transaction.setOrderPrice(coinSellBuyRequestDto.getOrderPrice());
		transaction.setOrderAmount(coinSellBuyRequestDto.getOrderAmount());
		transaction.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Seoul"))); // 거래 시간
		currentUser.addTransaction(transaction); // 트랜잭션 페이지에 추가하는 작업 연관관계로 묶여있는걸로 접근

		// 거래 타입에 따라 코인 보유량과 현금 잔액을 업데이트합니다.
		if ("buy".equalsIgnoreCase(coinSellBuyRequestDto.getOrderType())) {
			// 구매 시 처리
			processBuy(currentUser, coinInfo, coinSellBuyRequestDto);
		} else if ("sell".equalsIgnoreCase(coinSellBuyRequestDto.getOrderType())) {
			// 판매 시 처리
			processSell(currentUser, coinInfo, coinSellBuyRequestDto);
		} else {
			throw new CustomException(401, "유효하지 않은 거래 타입입니다.");
		}
	}

	// 주문 유효성 검사 및 조정
	private void validateAndAdjustOrder(User user, CoinSellBuyRequestDto request, CoinInfo coinInfo) {
		if ("buy".equalsIgnoreCase(request.getOrderType())) {
			// 구매 시 유효성 검사
			CashBalance cashBalance = user.getCashBalance();
			if (cashBalance == null) {
				throw new CustomException(404, "현금 잔액 정보가 없습니다.");
			}

			// 보유 현금보다 주문 금액이 큰 경우 오류
			if (cashBalance.getBalance() < request.getOrderAmount()) {
				throw new CustomException(401, "현금 잔액이 부족합니다. 현재 잔액: " + cashBalance.getBalance());
			}

			// 최소 주문금액 확인
			if (request.getOrderAmount() < 5000) {
				throw new CustomException(400, "최소 주문 금액은 5000원입니다. 현재 주문 금액: " + request.getOrderAmount());
			}

		} else if ("sell".equalsIgnoreCase(request.getOrderType())) {
			// 판매 시 유효성 검사
			Optional<CoinHolding> existingHolding = user.getCoinHoldings().stream()
					.filter(holding -> holding.getCoinInfo().getId().equals(coinInfo.getId())).findFirst();

			if (existingHolding.isEmpty()) {
				throw new CustomException(400, "판매할 코인을 보유하고 있지 않습니다.");
			}

			// 보유 수량보다 많이 판매하려고 하면 보유 수량으로 조정
			if (existingHolding.get().getHoldingQuantity() < request.getOrderQuantity()) {
				Double availableQuantity = existingHolding.get().getHoldingQuantity();
				request.setOrderQuantity(availableQuantity);
				// 주문 금액도 다시 계산
				request.setOrderAmount(Math.round(request.getOrderPrice() * availableQuantity));

				if (request.getOrderQuantity() < 0) {
					throw new CustomException(404, "판매할 코인이 없습니다.");
				}
			}

			if (request.getOrderQuantity() <= 0) {
				throw new CustomException(400, "최소 주문 수량은 0이 될 수없습니다. 현재 주문 수량: " + request.getOrderQuantity());
			}
		}
	}

	private void processBuy(User user, CoinInfo coinInfo, CoinSellBuyRequestDto request) {
		// 현금 잔액 락 획득
		CashBalance cashBalance = cashBalanceRepository.findByUserWithLock(user).orElseGet(() -> {
			CashBalance newBalance = new CashBalance(); // 만약 cashbalance가 없다면 새롭게 맵핑
			user.setCashBalance(newBalance);
			return newBalance;
		});

		// 코인 보유 정보 락 획득
		Optional<CoinHolding> existingHolding = coinHoldingRepository.findByUserAndCoinInfoWithLock(user, coinInfo);

		// 현금 잔액이 충분한지 확인
		if (cashBalance.getBalance() < request.getOrderAmount()) {
			throw new CustomException(403, "현금 잔액이 부족합니다. 현재 잔액: " + cashBalance.getBalance());
		}

		// 현금 잔액 감소
		cashBalance.setBalance(cashBalance.getBalance() - request.getOrderAmount());

		// 코인 보유량 업데이트
		if (existingHolding.isPresent()) {
			Double orderQuantity = request.getOrderAmount() / request.getOrderPrice();
			request.setOrderQuantity(orderQuantity);
			// 기존 보유량이 있는 경우 수량과 평균 매수가 업데이트
			CoinHolding holding = existingHolding.get();
			double newHoldingPrice = request.getOrderQuantity() * request.getOrderPrice();
			double newTotalQuantity = holding.getHoldingQuantity() + request.getOrderQuantity();

			holding.setHoldingQuantity(newTotalQuantity);
			holding.setHoldingPrice(holding.getHoldingPrice() + newHoldingPrice); // 항상 코인당 평균 매수가로 저장
		} else {
			// 가격으로 개수 다시 검증
			Double orderQuantity = request.getOrderAmount() / request.getOrderPrice();
			request.setOrderQuantity(orderQuantity);

			// 기존 보유량이 없는 경우 새로 생성
			CoinHolding newHolding = new CoinHolding();
			newHolding.setCoinInfo(coinInfo);
			newHolding.setHoldingQuantity(request.getOrderQuantity());
			newHolding.setHoldingPrice(request.getOrderPrice() * request.getOrderQuantity());
			user.addCoinHolding(newHolding);
		}
	}

	private void processSell(User user, CoinInfo coinInfo, CoinSellBuyRequestDto request) {
		// 코인 보유 정보 락 획득
		Optional<CoinHolding> existingHolding = coinHoldingRepository.findByUserAndCoinInfoWithLock(user, coinInfo);

		// 현금 잔액 락 획득
		CashBalance cashBalance = cashBalanceRepository.findByUserWithLock(user).orElseGet(() -> {
			CashBalance newBalance = new CashBalance();
			user.setCashBalance(newBalance);
			return newBalance;
		});

		if (existingHolding.isEmpty() || existingHolding.get().getHoldingQuantity() < request.getOrderQuantity()) {
			throw new CustomException(403, "코인 보유량이 부족합니다.");
		} else if (request.getOrderAmount() < 5000) {
			throw new CustomException(400, "최소 주문 금액은 5000원입니다. 현재 주문 금액: " + request.getOrderAmount());
		}
		if (request.getOrderQuantity() / existingHolding.get().getHoldingQuantity() <= 1) {
			CoinHolding holding = existingHolding.get();

			// 전체 수량 판매 요청인 경우
			if (Math.abs(holding.getHoldingQuantity() - request.getOrderQuantity()) < 0.00000001
					&& 0 <= Math.abs(holding.getHoldingQuantity() - request.getOrderQuantity())) { // 소수점 8자리 미만은 전부 판매
				// 현재가 조회
				Double currentPrice = getCurrentPrice(coinInfo.getTicker());
				// 보유량 전체 제거
				user.getCoinHoldings().remove(holding);
				// 정확한 금액 계산
				request.setOrderAmount(Math.round(holding.getHoldingQuantity() * currentPrice));
			} else {
				// 일부 수량 판매
				holding.setHoldingQuantity(holding.getHoldingQuantity() - request.getOrderQuantity());
				// 평균 매수가 비율 유지
				holding.setHoldingPrice(holding.getHoldingPrice()
						* (holding.getHoldingQuantity() / (holding.getHoldingQuantity() + request.getOrderQuantity())));
			}

			// 현금 잔액 증가
			cashBalance.setBalance(cashBalance.getBalance() + request.getOrderAmount());
		}
	}

	private CoinInfoResponseDto convertToCoinInfosResponse(CoinInfo coinInfo) {
		return new CoinInfoResponseDto(coinInfo.getTicker(), coinInfo.getKrCoinName());
	}
}