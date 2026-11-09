"""The punctuation classifier the keyboard runs: a small bidirectional
transformer encoder over the sentence so far, read at its first token.

Every shape here is read by `wm-engine/src/punct.rs`, which runs the same
arithmetic in int8: a factorised embedding (`e` wide, projected to `d`),
learned positions over `ctx` tokens, `layers` pre-norm blocks of full
self-attention and a GELU (tanh form) feed-forward, a final LayerNorm on the
`<s>` position and a linear head over the classes. Keep the two in step.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class Block(nn.Module):
    def __init__(self, d, heads, ff):
        super().__init__()
        self.h = heads
        self.ln1 = nn.LayerNorm(d)
        self.qkv = nn.Linear(d, 3 * d)
        self.o = nn.Linear(d, d)
        self.ln2 = nn.LayerNorm(d)
        self.w1 = nn.Linear(d, ff)
        self.w2 = nn.Linear(ff, d)

    def forward(self, x, mask):
        B, T, D = x.shape
        h = self.ln1(x)
        q, k, v = self.qkv(h).view(B, T, 3, self.h, D // self.h).permute(2, 0, 3, 1, 4)
        a = F.scaled_dot_product_attention(q, k, v, attn_mask=mask)
        x = x + self.o(a.transpose(1, 2).reshape(B, T, D))
        return x + self.w2(F.gelu(self.w1(self.ln2(x)), approximate="tanh"))


class PunctEncoder(nn.Module):
    def __init__(self, vocab, e=64, d=192, layers=4, heads=4, ff=512, ctx=48, classes=4):
        super().__init__()
        self.vocab, self.e, self.d, self.layers_n, self.heads, self.ff, self.ctx, self.classes = vocab, e, d, layers, heads, ff, ctx, classes
        self.emb = nn.Embedding(vocab, e)
        self.proj = nn.Linear(e, d)
        self.pos = nn.Parameter(torch.zeros(ctx, d))
        self.blocks = nn.ModuleList(Block(d, heads, ff) for _ in range(layers))
        self.ln = nn.LayerNorm(d)
        self.head = nn.Linear(d, classes)
        nn.init.normal_(self.emb.weight, std=0.02)
        nn.init.normal_(self.pos, std=0.02)

    def forward(self, ids, pad_mask):
        """`ids` [B, T]; `pad_mask` [B, T] True where a token is real."""
        T = ids.shape[1]
        x = self.proj(self.emb(ids)) + self.pos[:T]
        # Keys at padding are never attended to; every query row keeps at
        # least its own <s>, so no row is all -inf.
        mask = pad_mask[:, None, None, :]
        for b in self.blocks:
            x = b(x, mask)
        return self.head(self.ln(x[:, 0]))

    def config(self):
        return dict(e=self.e, d=self.d, layers=self.layers_n, heads=self.heads, ff=self.ff, ctx=self.ctx, vocab=self.vocab, classes=self.classes)
