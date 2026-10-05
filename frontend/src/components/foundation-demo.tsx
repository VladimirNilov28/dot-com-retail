"use client";

import { Button, Card } from "@heroui/react";
import { useId, useState } from "react";

export function FoundationDemo() {
  const [isExpanded, setIsExpanded] = useState(false);
  const detailsId = useId();

  return (
    <div className="grid items-start gap-6 md:grid-cols-2">
      <Card className="min-w-0">
        <Card.Header>
          <Card.Title>Try an interaction</Card.Title>
          <Card.Description>
            A HeroUI button with pointer and keyboard support.
          </Card.Description>
        </Card.Header>
        <Card.Content className="space-y-4">
          <p className="text-sm text-muted">
            Use the button below, or reach it with Tab and press Enter or Space.
          </p>
          <div id={detailsId} hidden={!isExpanded}>
            <p className="rounded-lg bg-surface-secondary p-4 text-sm">
              These details are controlled entirely on this page. No request is
              sent, and no business data is simulated.
            </p>
          </div>
        </Card.Content>
        <Card.Footer>
          <Button
            aria-controls={detailsId}
            aria-expanded={isExpanded}
            onPress={() => setIsExpanded((expanded) => !expanded)}
          >
            {isExpanded ? "Hide details" : "Show details"}
          </Button>
        </Card.Footer>
      </Card>

      <Card className="min-w-0" variant="secondary">
        <Card.Header>
          <Card.Title>Dark by design</Card.Title>
          <Card.Description>
            The same theme on the server, on the client, and after a reload.
          </Card.Description>
        </Card.Header>
        <Card.Content className="space-y-5">
          <p className="text-sm text-muted">
            Colors follow shared semantic tokens, not the device theme. System
            fonts keep typography available without external downloads.
          </p>
          <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
            <div className="space-y-2">
              <dt className="text-muted">Background</dt>
              <dd className="h-12 rounded-lg border border-border bg-background">
                <span className="sr-only">Dark page background</span>
              </dd>
            </div>
            <div className="space-y-2">
              <dt className="text-muted">Surface</dt>
              <dd className="h-12 rounded-lg border border-border bg-surface">
                <span className="sr-only">Elevated dark surface</span>
              </dd>
            </div>
            <div className="space-y-2">
              <dt className="text-muted">Accent</dt>
              <dd className="h-12 rounded-lg bg-accent">
                <span className="sr-only">Blue accent</span>
              </dd>
            </div>
          </dl>
        </Card.Content>
      </Card>
    </div>
  );
}
