import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { PostBody, PostCard } from "./post-card";
import type { FeedGroup } from "./types";

describe("promoted character card", () => {
  it("renders character imagery, candidate copy, and in-app evidence links", () => {
    const group: FeedGroup = {
      groupId: "promoted:patrick-rick-verdict-01",
      cells: [{
        type: "ad_header",
        cellId: "header",
        adId: "patrick-rick-verdict-01",
        author: "rick_sanchez",
        avatarUrl: "https://example.test/rick-avatar",
        label: "Ad · unofficial fan demo",
      }, {
        type: "ad_title",
        cellId: "title",
        adId: "patrick-rick-verdict-01",
        text: "Reddit, hire Patrick Jackson.",
      }, {
        type: "ad_media",
        cellId: "media",
        adId: "patrick-rick-verdict-01",
        destinationUrl: "https://patrickjackson.dev",
        displayDomain: "patrickjackson.dev",
        ctaLabel: "Review Patrick's work",
        items: [{
          creativeId: "rick",
          kind: "image",
          placeholderColor: 0xff102a43,
          aspectRatio: 1,
          altText: "Rick endorsing Patrick Jackson.",
          imageUrl: "https://example.test/rick",
          cacheKey: "rick",
        }],
      }, {
        type: "ad_summary",
        cellId: "summary",
        adId: "patrick-rick-verdict-01",
        text: "A technical endorsement for a client-platform engineer.",
        disclosureLabel: "AI-written fan-demo endorsement",
      }, {
        type: "ad_related_posts",
        cellId: "related",
        adId: "patrick-rick-verdict-01",
        disclosureLabel: "ReadThat engineering deep dives",
        posts: [{
          postId: "a0f45ae0-e445-5867-9090-89d79a2921e8",
          title: "SDUI feed: where server-driven UI helps—and where it stops",
          subreddit: "readthateng",
          score: 1,
        }],
      }, {
        type: "ad_actionbar",
        cellId: "actions",
        adId: "patrick-rick-verdict-01",
        commentCount: 0,
      }],
    };

    render(<MemoryRouter><PostCard group={group} /></MemoryRouter>);

    expect(screen.getByText("u/rick_sanchez")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Rick endorsing Patrick Jackson." })).toHaveAttribute(
      "src",
      "https://example.test/rick",
    );
    expect(screen.getByText("SDUI feed: where server-driven UI helps—and where it stops").closest("a")).toHaveAttribute(
      "href",
      "/post/a0f45ae0-e445-5867-9090-89d79a2921e8",
    );
    expect(screen.getByText("Review Patrick's work").closest("a")).toHaveAttribute(
      "href",
      "https://patrickjackson.dev",
    );
  });

  it("renders the case-study deep dive as a safe external link on post detail", () => {
    render(<PostBody
      body={"## Problem\n\nA coupled feed.\n\n[Deep dive: read the full case study](https://patrickjackson.dev/case-studies/readthat/sdui-feed/)"}
      detail
    />);

    expect(screen.getByRole("heading", { name: "Problem" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Deep dive: read the full case study" })).toHaveAttribute(
      "href",
      "https://patrickjackson.dev/case-studies/readthat/sdui-feed/",
    );
  });
});
