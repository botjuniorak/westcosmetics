'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('atomic', {
  init: () => ipcRenderer.invoke('init'),
  login: () => ipcRenderer.invoke('login'),
  cancelLogin: () => ipcRenderer.invoke('login:cancel'),
  logout: () => ipcRenderer.invoke('logout'),
  play: () => ipcRenderer.invoke('play'),
  saveSettings: (s) => ipcRenderer.invoke('settings:save', s),
  serverStatus: () => ipcRenderer.invoke('server:status'),
  openFolder: () => ipcRenderer.invoke('open:folder'),
  open: (url) => ipcRenderer.invoke('open', url),
  minimize: () => ipcRenderer.invoke('win:minimize'),
  close: () => ipcRenderer.invoke('win:close'),
  on: (channel, cb) => {
    const allowed = ['status', 'progress', 'closed', 'login:code', 'account'];
    if (allowed.includes(channel)) ipcRenderer.on(channel, (_e, data) => cb(data));
  }
});
