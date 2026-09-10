namespace AccessDemo2s
{
    partial class UpgradeForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.button_OpenFile = new System.Windows.Forms.Button();
            this.button_StopUpgrade = new System.Windows.Forms.Button();
            this.button_Upgrade = new System.Windows.Forms.Button();
            this.textBox_UpgrageState = new System.Windows.Forms.TextBox();
            this.progressBar_Upgrade = new System.Windows.Forms.ProgressBar();
            this.textBox_Path = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.SuspendLayout();
            // 
            // button_OpenFile
            // 
            this.button_OpenFile.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_OpenFile.Location = new System.Drawing.Point(439, 43);
            this.button_OpenFile.Name = "button_OpenFile";
            this.button_OpenFile.Size = new System.Drawing.Size(68, 23);
            this.button_OpenFile.TabIndex = 16;
            this.button_OpenFile.Text = "···";
            this.button_OpenFile.UseVisualStyleBackColor = true;
            this.button_OpenFile.Click += new System.EventHandler(this.button_OpenFile_Click);
            // 
            // button_StopUpgrade
            // 
            this.button_StopUpgrade.Enabled = false;
            this.button_StopUpgrade.Location = new System.Drawing.Point(301, 259);
            this.button_StopUpgrade.Name = "button_StopUpgrade";
            this.button_StopUpgrade.Size = new System.Drawing.Size(165, 30);
            this.button_StopUpgrade.TabIndex = 15;
            this.button_StopUpgrade.Text = "Stop()";
            this.button_StopUpgrade.UseVisualStyleBackColor = true;
            this.button_StopUpgrade.Click += new System.EventHandler(this.button_StopUpgrade_Click);
            // 
            // button_Upgrade
            // 
            this.button_Upgrade.Enabled = false;
            this.button_Upgrade.Location = new System.Drawing.Point(72, 259);
            this.button_Upgrade.Name = "button_Upgrade";
            this.button_Upgrade.Size = new System.Drawing.Size(197, 30);
            this.button_Upgrade.TabIndex = 14;
            this.button_Upgrade.Text = "Device Upgrade()";
            this.button_Upgrade.UseVisualStyleBackColor = true;
            this.button_Upgrade.Click += new System.EventHandler(this.button_Upgrade_Click);
            // 
            // textBox_UpgrageState
            // 
            this.textBox_UpgrageState.Location = new System.Drawing.Point(46, 125);
            this.textBox_UpgrageState.Multiline = true;
            this.textBox_UpgrageState.Name = "textBox_UpgrageState";
            this.textBox_UpgrageState.Size = new System.Drawing.Size(441, 109);
            this.textBox_UpgrageState.TabIndex = 13;
            // 
            // progressBar_Upgrade
            // 
            this.progressBar_Upgrade.Location = new System.Drawing.Point(46, 83);
            this.progressBar_Upgrade.Name = "progressBar_Upgrade";
            this.progressBar_Upgrade.Size = new System.Drawing.Size(441, 23);
            this.progressBar_Upgrade.TabIndex = 12;
            // 
            // textBox_Path
            // 
            this.textBox_Path.Location = new System.Drawing.Point(114, 44);
            this.textBox_Path.Name = "textBox_Path";
            this.textBox_Path.Size = new System.Drawing.Size(303, 23);
            this.textBox_Path.TabIndex = 11;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(17, 47);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(91, 14);
            this.label1.TabIndex = 7;
            this.label1.Text = "Path()：";
            // 
            // UpgradeForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(530, 317);
            this.Controls.Add(this.button_OpenFile);
            this.Controls.Add(this.button_StopUpgrade);
            this.Controls.Add(this.button_Upgrade);
            this.Controls.Add(this.textBox_UpgrageState);
            this.Controls.Add(this.progressBar_Upgrade);
            this.Controls.Add(this.textBox_Path);
            this.Controls.Add(this.label1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "UpgradeForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "Upgrade()";
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.Button button_OpenFile;
        private System.Windows.Forms.Button button_StopUpgrade;
        private System.Windows.Forms.Button button_Upgrade;
        private System.Windows.Forms.TextBox textBox_UpgrageState;
        private System.Windows.Forms.ProgressBar progressBar_Upgrade;
        private System.Windows.Forms.TextBox textBox_Path;
        private System.Windows.Forms.Label label1;
    }
}