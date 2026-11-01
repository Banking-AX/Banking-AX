package com.bankingax.core.reservation;
import java.util.List;
public interface StaffRankingPort { List<Long> rank(List<Long> candidateIds,String taskCode); }
