package org.secretjuju.kono.repository;

import java.util.List;
import java.util.Optional;

import org.secretjuju.kono.entity.CoinInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoinInfoRepository extends JpaRepository<CoinInfo, Integer> {
	Optional<CoinInfo> findByTicker(String ticker);
	Optional<CoinInfo> findByTickerAndActiveTrue(String ticker);
	List<CoinInfo> findAll();
	List<CoinInfo> findAllByActiveTrue();
}