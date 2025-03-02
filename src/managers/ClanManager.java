}
        
// Verify both players are in the clan
if (!clan.isMember(oldLeaderUuid) || !clan.isMember(newLeaderUuid)) {
    return false;
}

// Verify old leader is actually the leader
if (!clan.getLeaderUuid().equals(oldLeaderUuid)) {
    return false;
}

// Transfer leadership
clan.setLeaderUuid(newLeaderUuid);

// Update roles
ClanMember oldLeader = clan.getMember(oldLeaderUuid);
ClanMember newLeader = clan.getMember(newLeaderUuid);

oldLeader.setRole("OFFICER");
newLeader.setRole("LEADER");

// Save clan to persist changes
saveClan(clan);

return true;
}

public Clan getClan(String clanTag) {
return clans.get(clanTag);
}

public Clan getPlayerClan(UUID playerUuid) {
String clanTag = playerClanMap.get(playerUuid);
if (clanTag == null) {
    return null;
}
return getClan(clanTag);
}

public List<Clan> getAllClans() {
return new ArrayList<>(clans.values());
}

public List<Clan> getTopClans(int limit) {
List<Clan> clanList = new ArrayList<>(clans.values());
clanList.sort(Comparator.comparingInt(Clan::getRating).reversed());

if (clanList.size() <= limit) {
    return clanList;
} else {
    return clanList.subList(0, limit);
}
}

public boolean canClanParticipateInBattle(String clanTag) {
Clan clan = getClan(clanTag);
if (clan == null) {
    return false;
}

// Check if clan has minimum required members
int minMembers = Config.MIN_PLAYERS_PER_CLAN;
int onlineMembers = getOnlineMembersCount(clan);

return onlineMembers >= minMembers;
}

public int getOnlineMembersCount(Clan clan) {
int count = 0;
for (ClanMember member : clan.getMembers()) {
    Player player = Bukkit.getPlayer(member.getPlayerUuid());
    if (player != null && player.isOnline()) {
        count++;
    }
}
return count;
}

public List<Player> getOnlineMembers(Clan clan) {
List<Player> onlineMembers = new ArrayList<>();
for (ClanMember member : clan.getMembers()) {
    Player player = Bukkit.getPlayer(member.getPlayerUuid());
    if (player != null && player.isOnline()) {
        onlineMembers.add(player);
    }
}
return onlineMembers;
}

public void updateClanStats(String winnerTag, String loserTag) {
Clan winner = getClan(winnerTag);
Clan loser = getClan(loserTag);

if (winner != null) {
    winner.setWins(winner.getWins() + 1);
    updateClanRating(winner, true);
    saveClan(winner);
}

if (loser != null) {
    loser.setLosses(loser.getLosses() + 1);
    updateClanRating(loser, false);
    saveClan(loser);
}
}

private void updateClanRating(Clan clan, boolean isWinner) {
int currentRating = clan.getRating();
int ratingChange;

if (isWinner) {
    ratingChange = Config.RATING_WIN_POINTS;
} else {
    ratingChange = -Config.RATING_LOSS_POINTS;
    // Make sure rating doesn't go below 0
    if (currentRating + ratingChange < 0) {
        ratingChange = -currentRating;
    }
}

clan.setRating(currentRating + ratingChange);
}
}