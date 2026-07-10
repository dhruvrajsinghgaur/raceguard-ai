package com.raceguard.model;

import java.util.List;

/**
 * What Stage 2 (the LLM reasoning layer) produces for a single ProjectRisk.
 * Deliberately narrow: the LLM is NOT asked to decide whether something is a
 * risk (Stage 1 already proved that deterministically), and it is NOT asked
 * to invent a fix from scratch (Stage 1 already computed defensible options
 * via FixSuggestionEngine). Its only two jobs are to narrate the risk in
 * plain English and to pick/justify the best-fitting fix among the options
 * it was given. This keeps the LLM's job small, cheap, and hard to get
 * embarrassingly wrong in front of judges.
 */
public class RiskExplanation {

    public String pattern;
    public String owningClass;
    public String field;

    public String explanation;

    public List<String> interleaving; // predicted thread-interleaving trace, e.g. "T1: reads weaponsOnGround.size() = 3"

    public String recommendedFixApproach; // must exactly match one FixSuggestion.approach from the input
    public String recommendedFixRationale;
}