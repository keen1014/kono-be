package org.secretjuju.kono.repository;

import java.util.Optional;

import org.secretjuju.kono.entity.CoinInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinRepository extends JpaRepository<CoinInfo, Integer> {
	Optional<CoinInfo> findByTicker(String ticker);

	// Active 상태인 코인만 조회하는 편의 메서드
	Optional<CoinInfo> findByTickerAndActiveTrue(String ticker);
	java.util.List<CoinInfo> findAllByActiveTrue();
}