package com.rollbound.account;

import lombok.Getter;

@Getter
public class AccountChanged
{
	private final long hash;
	private final String playerName;
	private final boolean loggedIn;

	public AccountChanged(long hash, String playerName)
	{
		this.hash = hash;
		this.playerName = playerName;
		this.loggedIn = hash != -1;
	}

	@Override
	public String toString() {
		return "AccountChanged{" +
				"hash=" + hash +
				", playerName='" + playerName + '\'' +
				", loggedIn=" + loggedIn +
				'}';
	}
}
