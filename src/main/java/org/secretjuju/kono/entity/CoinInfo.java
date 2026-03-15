package org.secretjuju.kono.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coin_info")
@Getter
@Setter
@NoArgsConstructor
public class CoinInfo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(name = "kr_coin_name", nullable = false, unique = true, length = 100)
	private String krCoinName;

	@Column(name = "ticker", nullable = false, unique = true, length = 50)
	private String ticker;

	// 활성화 여부: true일 때 서비스에서 사용
	@Column(name = "active", nullable = false)
	private Boolean active = true;

	// 양방향 관계 설정
	@OneToMany(mappedBy = "coinInfo", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
	private List<CoinTransaction> transactions = new ArrayList<>();

	@OneToMany(mappedBy = "coinInfo", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
	private List<CoinHolding> coinHoldings = new ArrayList<>();

	@OneToMany(mappedBy = "coinInfo", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
	private List<CoinFavorite> coinFavorites = new ArrayList<>();

}
