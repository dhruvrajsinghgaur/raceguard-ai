package com.raceguard.model;

import java.util.List;

public class ProjectRisk {

    public String field;

    public String owningClass;

    public String fieldType;

    public List<String> writers;

    public List<String> readers;

    public List<String> unguardedWriters;

    public String severity;

    public int signalCount;

    public List<String> reasons;

    public String summary;

    public String pattern;

    public List<FixSuggestion> fixSuggestions;

}