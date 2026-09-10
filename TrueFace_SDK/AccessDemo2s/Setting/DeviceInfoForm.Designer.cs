namespace AccessDemo2s
{
    partial class DeviceInfoForm
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
            this.groupBox_Caps = new System.Windows.Forms.GroupBox();
            this.txt_Caps = new System.Windows.Forms.TextBox();
            this.txt_Version = new System.Windows.Forms.TextBox();
            this.btn_Get = new System.Windows.Forms.Button();
            this.groupBox_Version = new System.Windows.Forms.GroupBox();
            this.groupBox_Caps.SuspendLayout();
            this.groupBox_Version.SuspendLayout();
            this.SuspendLayout();
            // 
            // groupBox_Caps
            // 
            this.groupBox_Caps.Controls.Add(this.txt_Caps);
            this.groupBox_Caps.Location = new System.Drawing.Point(12, 12);
            this.groupBox_Caps.Name = "groupBox_Caps";
            this.groupBox_Caps.Size = new System.Drawing.Size(510, 345);
            this.groupBox_Caps.TabIndex = 3;
            this.groupBox_Caps.TabStop = false;
            this.groupBox_Caps.Text = "Capability()";
            // 
            // txt_Caps
            // 
            this.txt_Caps.Location = new System.Drawing.Point(6, 20);
            this.txt_Caps.Multiline = true;
            this.txt_Caps.Name = "txt_Caps";
            this.txt_Caps.ReadOnly = true;
            this.txt_Caps.ScrollBars = System.Windows.Forms.ScrollBars.Vertical;
            this.txt_Caps.Size = new System.Drawing.Size(498, 319);
            this.txt_Caps.TabIndex = 0;
            // 
            // txt_Version
            // 
            this.txt_Version.Location = new System.Drawing.Point(7, 20);
            this.txt_Version.Multiline = true;
            this.txt_Version.Name = "txt_Version";
            this.txt_Version.ReadOnly = true;
            this.txt_Version.RightToLeft = System.Windows.Forms.RightToLeft.No;
            this.txt_Version.ScrollBars = System.Windows.Forms.ScrollBars.Vertical;
            this.txt_Version.Size = new System.Drawing.Size(497, 319);
            this.txt_Version.TabIndex = 1;
            // 
            // btn_Get
            // 
            this.btn_Get.Location = new System.Drawing.Point(956, 363);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(96, 37);
            this.btn_Get.TabIndex = 5;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // groupBox_Version
            // 
            this.groupBox_Version.Controls.Add(this.txt_Version);
            this.groupBox_Version.Location = new System.Drawing.Point(542, 12);
            this.groupBox_Version.Name = "groupBox_Version";
            this.groupBox_Version.Size = new System.Drawing.Size(510, 345);
            this.groupBox_Version.TabIndex = 4;
            this.groupBox_Version.TabStop = false;
            this.groupBox_Version.Text = "Version()";
            // 
            // DeviceInfoForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(1064, 412);
            this.Controls.Add(this.groupBox_Caps);
            this.Controls.Add(this.groupBox_Version);
            this.Controls.Add(this.btn_Get);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "DeviceInfoForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "DeviceInfo()";
            this.groupBox_Caps.ResumeLayout(false);
            this.groupBox_Caps.PerformLayout();
            this.groupBox_Version.ResumeLayout(false);
            this.groupBox_Version.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.GroupBox groupBox_Caps;
        private System.Windows.Forms.TextBox txt_Caps;
        private System.Windows.Forms.TextBox txt_Version;
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.GroupBox groupBox_Version;
    }
}