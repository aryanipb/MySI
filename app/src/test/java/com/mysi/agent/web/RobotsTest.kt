package com.mysi.agent.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsTest {
    @Test
    fun theMySIGroupWinsOverTheWildcard() {
        val r = Robots.parse(Fixtures.text("robots.txt"))
        assertFalse(r.allowed("/drafts/x"))
        assertTrue(r.allowed("/drafts/public/y"))
        // The * group does not apply once a MySI group exists.
        assertTrue(r.allowed("/private/a"))
        assertTrue(r.allowed("/"))
    }

    @Test
    fun wildcardGroupRulesLongestMatchAndAllowTies() {
        val r = Robots.parse("User-agent: *\nDisallow: /private/\nDisallow: /search\nAllow: /search/about\nDisallow: /*.json$\nDisallow:\n")
        assertFalse(r.allowed("/private/a"))
        assertTrue(r.allowed("/privateer"))
        assertFalse(r.allowed("/search?q=x"))
        assertTrue(r.allowed("/search/about"))
        assertFalse(r.allowed("/api/data.json"))
        assertTrue(r.allowed("/api/data.json?x=1"))
        assertTrue(r.allowed("/index.html"))
        assertTrue(Robots.parse("User-agent: *\nDisallow: /a\nAllow: /a\n").allowed("/a"))
        assertTrue(Robots.parse("User-agent: GPTBot\nDisallow: /\n").allowed("/anything"))
        assertTrue(Robots.parse("").allowed("/"))
        assertFalse(Robots.DISALLOW_ALL.allowed("/x"))
    }
}
