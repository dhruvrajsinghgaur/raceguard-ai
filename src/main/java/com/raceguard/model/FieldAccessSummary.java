package com.raceguard.model;

import java.util.ArrayList;
import java.util.List;

public class FieldAccessSummary {

    public List<String> readers = new ArrayList<>();

    public List<String> writers = new ArrayList<>();

    public List<String> rmw = new ArrayList<>();

    public List<String> unguardedReaders = new ArrayList<>();

    public List<String> unguardedWriters = new ArrayList<>();

    public List<String> unguardedRmw = new ArrayList<>();

    public boolean concurrentTrigger;
}