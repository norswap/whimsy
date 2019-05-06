package norswap.autumn;

import norswap.autumn.parsers.Not;
import norswap.autumn.visitors.WellFormednessChecker;
import norswap.utils.ArrayListLong;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The context associated with <i>a parse</i>, which is the the invocation of a (root) parser on
 * some input — either a String ({@link #string}) or a list ({@link #list}).
 *
 * <p>Instances of this class cannot be created by the user, instead they are generated by one of
 * the {@link Autumn} {@code .run} methods. However, custom {@link Parser} implementations
 * can (and should) access this class.
 *
 * <p>Most fields of this class are public in order to enable advanced parser implementations, but
 * it is often not necessary to touch them at all. See the relevant part of the Autumn manual for
 * more information.
 */
public final class Parse
{
    // ---------------------------------------------------------------------------------------------

    /**
     * Position within the input.
     */
    public int pos = 0;

    // ---------------------------------------------------------------------------------------------

    /**
     * Position of the furthest encountered error, or -1 if no error have been encountered.
     */
    public int error = -1;

    // ---------------------------------------------------------------------------------------------

    /**
     * One of the two forms of input the parse may have.
     */
    public final String string;

    // ---------------------------------------------------------------------------------------------

    /**
     * One of the two forms of input the parse may have.
     */
    public final List<?> list;

    // ---------------------------------------------------------------------------------------------

    /**
     * The parse options used to construct this parse object.
     */
    public final ParseOptions options;

    // ---------------------------------------------------------------------------------------------

    /**
     * The list of side-effects that have been applied during this parse.
     */
    public final Log log = new Log();

    // ---------------------------------------------------------------------------------------------

    /**
     * A stack that can be used to build ASTs.
     */
    public final SideEffectingArrayStack stack = new SideEffectingArrayStack(log);

    // ---------------------------------------------------------------------------------------------

    /**
     * Use this map to store custom parsing state data. If state changes must be undone when
     * backtracking (as is usual), the state data should usually be modified exclusively through a
     * {@link SideEffect}.
     *
     * <p>Always use {@link ParseState} to transparently access this map (which also yield
     * increased performance via caching).
     */
    public final Map<Object, Object> state_data = new HashMap<>();

    // ---------------------------------------------------------------------------------------------

    /**
     * List of {@link ParseState} used during this parse, i.e. parse states whose data
     * are registered in {@link #state_data}.
     */
    ArrayList<ParseState<?>> parse_states = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------

    /**
     * The current parser invocation stack if {@link ParseOptions#record_call_stack} is set,
     * null otherwise.
     *
     * <p>Only access if required (and check if the option is set!). No base parsers use this.
     */
    public ParserCallStack call_stack;

    // ---------------------------------------------------------------------------------------------

    /**
     * If {@link ParseOptions#record_call_stack} is set, the stack of parser invocations that lead
     * to the furthest error (at position {@link #error}), or null if there were no parse errors.
     * Otherwise, always null.
     *
     * <p>Only access if required (and check if the option is set!). Only the {@link Not} base
     * parser uses this.
     */
    public ParserCallStack error_call_stack;

    // ---------------------------------------------------------------------------------------------

    /**
     * A stack used to record the execution time of completed parser invocations in tracing mode
     * ({@link ParseOptions#trace}).
     */
    final ArrayListLong trace_timings;

    // ---------------------------------------------------------------------------------------------

    /**
     * Maps parser names to a set of parser metrics.
     *
     * <p>Can be reused accross parses using {@link ParseOptions#metrics}.
     */
    final ParseMetrics parse_metrics;

    // ---------------------------------------------------------------------------------------------

    private Parse (String string, List<?> list, ParseOptions options)
    {
        options = options != null ? options : ParseOptions.get();
        this.string = string;
        this.list = list;
        this.options = options;
        call_stack = options.record_call_stack ? new ParserCallStack() : null;
        trace_timings = options.trace ? new ArrayListLong(256) : null;
        parse_metrics = options.trace ? options.metrics.get() : null;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * @see Autumn#parse
     */
    static ParseResult run (Parser parser, String string, List<?> list, ParseOptions options)
    {
        if (options.well_formed_check)
        {
            WellFormednessChecker checker = options.well_formed_checker.get();

            if (!checker.well_formed(parser))
            {
                StringBuilder b = new StringBuilder();

                for (Parser p: checker.left_recursives)
                    b   .append("\n- Left-recursive parser cycle detected, passing through parser: ")
                        .append(p);

                for (Parser p: checker.nullable_repetitions)
                    b   .append("\n- Nullable repetition detected: ")
                        .append(p);

                throw new MalformedGrammarError(b.toString(), checker);
            }
        }

        Parse parse = new Parse(string, list, options);
        Throwable thrown = null;
        boolean success = false;
        try { success = parser.parse(parse); }
        catch (StackOverflowError e) { throw e; } // (1)
        catch (Throwable t) { thrown = t; }
        finally {
            for (ParseState<?> state: parse.parse_states)
                state.discard_cache(parse);
        }

        // (1) wrapped in PotentiallyMalformedGrammarError in Autumn#parse

        boolean full_match
            = success && parse.pos == parse.input_length();

        int match_size
            = success ? parse.pos : -1;

        int error_position
            = full_match
                ? -1
                : thrown != null
                    ? parse.pos
                    : parse.error;

        ParserCallStack error_call_stack
            = thrown != null
                ? parse.call_stack
                : full_match
                    ? null
                    : parse.call_stack;

        return new ParseResult(
            success,
            full_match,
            match_size,
            thrown,
            parser,
            options,
            error_position,
            parse.stack,
            parse.state_data,
            error_call_stack,
            parse.parse_metrics);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A generic method returning the size of the input that abstracts over whether this parse
     * is over a string or a list.
     */
    public int input_length()
    {
        return string != null
            ? string.length()
            : list.size();
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the character from {@link #string} at the given index,
     * or 0 if {@code index == string.length}.
     */
    public char char_at (int index)
    {
        assert string != null;
        return index != string.length()
            ? string.charAt(index)
            : 0;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the object from {@link #list} at the given index,
     * or null if {@code index == list.size()}.
     */
    public Object object_at (int index)
    {
        assert list != null;
        return index != list.size()
            ? list.get(index)
            : null;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns true if the given string candidate appears in the parse's input string at the given
     * index. This function is safe even if the string candidate is longer than the remaining input.
     */
    public boolean match (int index, String candidate)
    {
        assert string != null;
        int end = Math.min(pos + candidate.length(), string.length());
        return candidate.equals(string.substring(pos, end));
    }

    // ---------------------------------------------------------------------------------------------
}
