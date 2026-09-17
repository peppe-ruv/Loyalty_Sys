/* Guscio del pannello Payload (RF-40): tutte le rotte /admin e /api sono generate da @payloadcms/next. */
import type { ServerFunctionClient } from 'payload';
import config from '@payload-config';
import { RootLayout, handleServerFunctions } from '@payloadcms/next/layouts';
import React from 'react';

import { importMap } from './admin/importMap.js';
import './custom.scss';

type Args = {
  children: React.ReactNode;
};

const serverFunction: ServerFunctionClient = async function (args) {
  'use server';
  return handleServerFunctions({ ...args, config, importMap });
};

export default function Layout({ children }: Args) {
  return (
    <RootLayout config={config} importMap={importMap} serverFunction={serverFunction}>
      {children}
    </RootLayout>
  );
}
