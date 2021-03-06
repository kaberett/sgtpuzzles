# run this file with
#   gap -b -q < /dev/null group.gap | perl -pe 's/\\\n//s' | indent -kr

Print("/* ----- data generated by group.gap begins ----- */\n\n");
Print("struct group {\n    unsigned long autosize;\n");
Print("    int order, ngens;\n    const char *gens;\n};\n");
Print("struct groups {\n    int ngroups;\n");
Print("    const struct group *groups;\n};\n\n");
Print("static const struct group groupdata[] = {\n");
offsets := [0];
offset := 0;
for n in [2..26] do
  Print("    /* order ", n, " */\n");
  for G in AllSmallGroups(n) do

    # Construct a representation of the group G as a subgroup
    # of a permutation group, and find its generators in that
    # group.

    # GAP has the 'IsomorphismPermGroup' function, but I don't want
    # to use it because it doesn't guarantee that the permutation
    # representation of the group forms a Cayley table. For example,
    # C_4 could be represented as a subgroup of S_4 in many ways,
    # and not all of them work: the group generated by (12) and (34)
    # is clearly isomorphic to C_4 but its four elements do not form
    # a Cayley table. The group generated by (12)(34) and (13)(24)
    # is OK, though.
    #
    # Hence I construct the permutation representation _as_ the
    # Cayley table, and then pick generators of that. This
    # guarantees that when we rebuild the full group by BFS in
    # group.c, we will end up with the right thing.

    ge := Elements(G);
    gi := [];
    for g in ge do
      gr := [];
      for h in ge do
        k := g*h;
	for i in [1..n] do
	  if k = ge[i] then
	    Add(gr, i);
	  fi;
	od;
      od;
      Add(gi, PermList(gr));
    od;

    # GAP has the 'GeneratorsOfGroup' function, but we don't want to
    # use it because it's bad at picking generators - it thinks the
    # generators of C_4 are [ (1,2)(3,4), (1,3,2,4) ] and that those
    # of C_6 are [ (1,2,3)(4,5,6), (1,4)(2,5)(3,6) ] !

    gl := ShallowCopy(Elements(gi));
    Sort(gl, function(v,w) return Order(v) > Order(w); end);

    gens := [];
    for x in gl do
      if gens = [] or not (x in gp) then
        Add(gens, x);
	gp := GroupWithGenerators(gens);
      fi;
    od;

    # Construct the C representation of the group generators.
    s := [];
    for x in gens do
      if Size(s) > 0 then
        Add(s, '"');
        Add(s, ' ');
        Add(s, '"');
      fi;
      sep := "\\0";
      for i in ListPerm(x) do
        chars := "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Add(s, chars[i]);
      od;
    od;
    s := JoinStringsWithSeparator(["    {", String(Size(AutomorphismGroup(G))),
                                   "L, ", String(Size(G)),
                                   ", ", String(Size(gens)),
                                   ", \"", s, "\"},\n"],"");
    Print(s);
    offset := offset + 1;
  od;
  Add(offsets, offset);
od;
Print("};\n\nstatic const struct groups groups[] = {\n");
Print("    {0, NULL}, /* trivial case: 0 */\n");
Print("    {0, NULL}, /* trivial case: 1 */\n");
n := 2;
for i in [1..Size(offsets)-1] do
  Print("    {", offsets[i+1] - offsets[i], ", groupdata+",
        offsets[i], "}, /* ", i+1, " */\n");
od;
Print("};\n\n/* ----- data generated by group.gap ends ----- */\n");
quit;
